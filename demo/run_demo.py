"""Collect live demo evidence, or inspect saved evidence without calling a model.

Structural checks and literal observations are not model-quality scores.
"""
import argparse
import hashlib
import json
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

SCENARIOS = ('composite', 'memory_first', 'memory_second')
MEMORY_ORDER = 'A20260914002'


def assess_evidence(records):
    """Check routing/memory/tool fields; never use verified as execution success."""
    responses = records.get('responses', {})
    composite = responses.get('composite', {})
    first = responses.get('memory_first', {})
    second = responses.get('memory_second', {})
    agents = set(composite.get('agent_types', []))
    agents.update(composite.get('supporting_agents', []))
    agents.add(composite.get('primary_agent'))
    resolved = second.get('resolved_entities', {})
    current = second.get('current_entities', {})
    tool_checks = {}
    for name in SCENARIOS:
        trace = responses.get(name + '_trace', {}).get('trace', {})
        calls = [call for call in trace.get('tool_calls', [])
                 if call.get('tool_name') == 'knowledge_search']
        tool_checks[name + '_knowledge_trace_succeeded'] = bool(calls) and all(
            call.get('success') is True and call.get('fallback_used') is False for call in calls)
    checks = {
        'all_responses_present': all(isinstance(responses.get(name), dict)
                                    and bool(responses[name].get('response')) for name in SCENARIOS),
        'technical_and_billing': {'technical', 'billing'}.issubset(agents),
        'same_memory_conversation': bool(first.get('conv_id')) and first.get('conv_id') == second.get('conv_id'),
        'second_turn_order_comes_from_history': not current.get('order_id') and MEMORY_ORDER in resolved.get('order_id', []),
        'amount_resolved_in_second_turn': '299元' in resolved.get('amount', []),
        'second_answer_quotes_order': MEMORY_ORDER in second.get('response', ''),
        'knowledge_used': all(responses.get(name, {}).get('knowledge_used') is True for name in SCENARIOS),
        **tool_checks,
    }
    observations = {}
    for name in SCENARIOS:
        response = responses.get(name, {})
        answer = response.get('response', '')
        observations[name] = {
            'answer_characters': len(answer),
            'latency_ms': response.get('latency_ms'),
            'model_verified': response.get('verified'),
            'model_grounded': response.get('grounded'),
            # Literal occurrence only: negated sentences are not action claims.
            'mentions_legacy_24_hour_rule': '24小时' in answer.replace(' ', ''),
            'mentions_ticket': '工单' in answer,
        }
    return checks, observations


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--output', default=None)
    parser.add_argument('--inspect-evidence', type=Path,
                        help='Read existing evidence JSON without HTTP or model calls.')
    args = parser.parse_args()
    if args.inspect_evidence:
        source = json.loads(args.inspect_evidence.read_text(encoding='utf-8'))
        checks, observations = assess_evidence(source)
        records = {
            'mode': 'offline_evidence_inspection', 'source': str(args.inspect_evidence),
            'checks': checks, 'observations': observations, 'model_quality_status': 'not_evaluated',
            'note': 'Saved observations only; does not rerun the model or validate current prompts.',
        }
        output = Path(args.output or 'demo/evidence-inspection.json')
        if output.resolve() == args.inspect_evidence.resolve():
            parser.error('Inspection output must not overwrite source evidence.')
    else:
        parsed = urllib.parse.urlparse(args.base_url)
        if parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost'):
            parser.error('This demo imports synthetic knowledge. Use a local HTTP demo instance.')
        base = args.base_url.rstrip('/')

        def request(path, data=None):
            body = None if data is None else json.dumps(data, ensure_ascii=False).encode('utf-8')
            req = urllib.request.Request(base + path, data=body, headers={'Content-Type': 'application/json'})
            with urllib.request.urlopen(req, timeout=180) as response:
                return json.load(response)

        suffix = str(time.time_ns())
        user = 'interview-' + suffix
        conv = 'memory-' + suffix
        knowledge_path = Path(__file__).with_name('knowledge.json')
        records = {
            'schema_version': 2, 'mode': 'live_demo', 'synthetic_data': True,
            'started_at': datetime.now(timezone.utc).isoformat(),
            'knowledge_sha256': hashlib.sha256(knowledge_path.read_bytes()).hexdigest(),
            'user_id': user, 'conv_id': conv, 'responses': {},
            'model_quality_status': 'pending_manual_review',
        }
        try:
            records['health'] = request('/health')
            payload = json.loads(knowledge_path.read_text(encoding='utf-8'))
            records['import'] = request('/knowledge/add', payload)

            def chat(name, message, conversation):
                response = request('/chat', {'message': message, 'user_id': user, 'conv_id': conversation})
                records['responses'][name] = response
                rid = response.get('request_id')
                if rid:
                    records['responses'][name + '_trace'] = request('/trace/tool/' + urllib.parse.quote(rid, safe=''))

            chat('composite', '我登录失败并提示401，订单#A20260914001查不到，而且银行卡重复扣款299元', 'composite-' + suffix)
            chat('memory_first', '我的订单号是#A20260914002，重复扣款299元，请记住这笔订单。', conv)
            chat('memory_second', '那这笔订单怎么申请退款？', conv)
        except Exception as exc:
            # Never store raw HTTP/model errors that may contain credentials.
            records['failure_type'] = type(exc).__name__
        records['checks'], records['observations'] = assess_evidence(records)
        records['manual_checks'] = [
            '回答是否分技术与账务两部分，且没有重复索要已知订单号、金额',
            '是否把会话引用订单误解成创建工单，或承诺永久保存信息',
            '若引用退款政策，是否分别说明申请审核1–3个工作日、审核通过后到账5–7个工作日；未编造核验时限',
            '是否虚构已退款、已建单或已查询支付流水',
            '第二轮自然语言回答是否承接第一轮订单与金额',
            '是否先给结论、保留必要步骤且避免重复政策；长度仅作观察，不作为质量分数',
        ]
        output = Path(args.output or 'demo/latest-result.json')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'evidence': str(output), 'checks': records['checks'],
                      'model_quality_status': records['model_quality_status'],
                      'failure_type': records.get('failure_type')}, ensure_ascii=False))
    if records.get('failure_type') or not all(records['checks'].values()):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
