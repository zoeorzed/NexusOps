"""Collect bounded local business-validation evidence; content review remains explicit.

The service must use an isolated, fresh knowledge directory. Only synthetic demo
knowledge is imported. The independent /search calls do not reveal /chat context.
"""
import argparse
import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSESSMENT_VERSION = 2


def as_mapping(value):
    return value if isinstance(value, dict) else {}


def as_list(value):
    return value if isinstance(value, list) else []


def validate_dataset(dataset):
    refs = dataset['reference_documents']
    ids = [ref['id'] for ref in refs]
    if len(ids) != len(set(ids)):
        raise ValueError('Duplicate reference IDs')
    for ref in refs:
        expected = hashlib.md5((ref['title'] + '_0_' + ref['content'][:50]).encode('utf-8')).hexdigest()
        if ref['chunk'] != 0 or ref['id'] != expected:
            raise ValueError('Reference ID does not match frozen single-chunk document')
    for group in ('chat_cases', 'retrieval_cases'):
        names = [case['id'] for case in dataset[group]]
        if len(names) != len(set(names)):
            raise ValueError('Duplicate case IDs')
        for case in dataset[group]:
            expected_ids = case.get('reference_ids', case.get('expected_reference_ids', []))
            if not set(expected_ids).issubset(ids):
                raise ValueError('Unknown reference ID')
            if group == 'retrieval_cases' and bool(expected_ids) != case['answerable']:
                raise ValueError('Answerability contradicts references')
    if not 1 <= len(dataset['chat_cases']) <= 14:
        raise ValueError('Chat run must be bounded to at most 14 requests')


def check(name, passed, expected=None, actual=None):
    result = {'name': name, 'passed': bool(passed)}
    if expected is not None:
        result['expected'] = expected
    if actual is not None:
        result['actual'] = actual
    return result


def assess_chat(case, record, prior_records):
    response = as_mapping(record.get('response'))
    trace_wrapper = as_mapping(record.get('trace'))
    trace = as_mapping(trace_wrapper.get('trace'))
    payload = as_mapping(record.get('request'))
    response_present = isinstance(response.get('response'), str) and bool(response['response'].strip())
    checks = [check('response_present', response_present),
              check('request_completed', 'failure_type' not in record and bool(response)),
              check('conversation_matches', bool(payload.get('conv_id')) and response.get('conv_id') == payload.get('conv_id')),
              check('trace_found', trace_wrapper.get('found') is True),
              check('trace_request_matches', bool(response.get('request_id')) and trace.get('request_id') == response.get('request_id')),
              check('trace_client_identifiers_match', bool(payload) and trace.get('user_id') == payload.get('user_id')
                    and trace.get('conversation_id') == payload.get('conv_id'))]
    actual_agents = [item for item in as_list(response.get('agent_types')) if isinstance(item, str)]
    if case.get('expected_agents'):
        checks.append(check('executed_agents_cover_expected_domains',
                            set(case['expected_agents']).issubset(actual_agents),
                            case['expected_agents'], actual_agents))
    if 'expected_primary_agent' in case:
        checks.append(check('primary_route_matches', response.get('primary_agent') == case['expected_primary_agent'],
                            case['expected_primary_agent'], response.get('primary_agent')))
    if 'expected_escalated' in case:
        checks.append(check('escalation_flag_matches', response.get('escalated') is case['expected_escalated'],
                            case['expected_escalated'], response.get('escalated')))
        checks.append(check('trace_escalation_matches', trace.get('escalated') is response.get('escalated')))
    calls = [call for call in as_list(trace.get('tool_calls')) if isinstance(call, dict)]
    knowledge = [call for call in calls if call.get('tool_name') == 'knowledge_search']
    expect_knowledge = case.get('expect_knowledge_trace', case.get('expected_primary_agent') != 'escalation')
    if expect_knowledge:
        checks.append(check('knowledge_trace_present', bool(knowledge)))
    if knowledge:
        checks.append(check('knowledge_trace_success_without_reported_fallback', all(
            call.get('success') is True and call.get('fallback_used') is False for call in knowledge)))
    execution = [call for call in calls if str(call.get('tool_name', '')).startswith('agent_execution:')]
    checks.append(check('no_reported_agent_execution_failure', all(call.get('success') is True for call in execution)))
    for expected_key, response_key in [('expected_current_entities', 'current_entities'),
                                       ('expected_resolved_entities', 'resolved_entities')]:
        for entity, values in case.get(expected_key, {}).items():
            actual = as_list(as_mapping(response.get(response_key)).get(entity))
            checks.append(check(response_key + '_' + entity + '_contains_expected',
                                all(value in actual for value in values), values, actual))
    for expected_key, response_key in [('expected_current_absent', 'current_entities'),
                                       ('expected_resolved_absent', 'resolved_entities')]:
        for entity in case.get(expected_key, []):
            actual = as_mapping(response.get(response_key)).get(entity)
            checks.append(check(response_key + '_' + entity + '_empty',
                                isinstance(actual, list) and not actual,
                                [], actual))
    for value in case.get('forbidden_answer_values', []):
        checks.append(check('unconfirmed_order_not_disclosed_' + value,
                            response_present and value not in response.get('response', '')))
    if case.get('must_follow'):
        previous = prior_records.get(case['must_follow'], {})
        previous_response = as_mapping(previous.get('response'))
        checks.append(check('history_seed_completed', bool(previous_response.get('response'))))
        checks.append(check('same_conversation_as_seed', bool(previous_response.get('conv_id'))
                            and previous_response.get('conv_id') == response.get('conv_id')))
    return checks


def assess_retrieval(dataset, records):
    """Failures stay in answerable denominator; no-answer cases have no rank score."""
    refs = {ref['id']: ref for ref in dataset['reference_documents']}
    observed_matching = set()
    details = []
    hits = 0
    reciprocal_ranks = 0.0
    answerable_count = 0
    request_failures = 0
    for case in dataset['retrieval_cases']:
        record = records.get(case['id'], {})
        response = as_mapping(record.get('response'))
        raw_results = response.get('results')
        failed = 'failure_type' in record or not isinstance(raw_results, list)
        if not isinstance(raw_results, list):
            raw_results = []
        request_failures += int(failed)
        returned_ids = []
        positions = []
        catalog_mismatches = []
        fallback_seen = False
        first_relevant_rank = None
        # Rank is the actual response position. Invalid, duplicate or mismatched
        # entries still consume a rank; deleting them would inflate MRR.
        for position, item in enumerate(raw_results[:case['top_k']], 1):
            if not isinstance(item, dict):
                positions.append({'position': position, 'id': None, 'reference_verified': False})
                continue
            rid = item.get('id') if isinstance(item.get('id'), str) else None
            returned_ids.append(rid)
            position_record = {'position': position, 'id': rid, 'reference_verified': False}
            positions.append(position_record)
            if rid == 'fallback' or as_mapping(item.get('metadata')).get('fallback') is True:
                fallback_seen = True
                continue
            ref = refs.get(rid)
            if ref:
                matches = item.get('title') == ref['title'] and item.get('content') == ref['content'] and item.get('chunk') == ref['chunk']
                if matches:
                    observed_matching.add(ref['id'])
                    position_record['reference_verified'] = True
                    if first_relevant_rank is None and rid in case['expected_reference_ids']:
                        first_relevant_rank = position
                else:
                    catalog_mismatches.append(rid)
        rank = None
        if case['answerable']:
            answerable_count += 1
            if not failed and not fallback_seen:
                rank = first_relevant_rank
            if rank is not None:
                hits += 1
                reciprocal_ranks += 1.0 / rank
        details.append({'case_id': case['id'], 'answerable': case['answerable'],
                        'expected_reference_ids': case['expected_reference_ids'],
                        'returned_ids': returned_ids, 'returned_positions': positions,
                        'rank_of_first_relevant': rank,
                        'request_failed': failed, 'fallback_document_seen': fallback_seen,
                        'reference_content_mismatches': catalog_mismatches,
                        'no_answer_content_review': 'not_applicable' if case['answerable'] else 'pending_semantic_review'})
    return {
        'assessment_version': ASSESSMENT_VERSION,
        'scope': '8 synthetic answerable candidates; independent /search including rewrite/rerank over the entire isolated corpus; not chat-grounding accuracy',
        'answerable_count': answerable_count, 'hit_count': hits, 'top_k': 3,
        'hit_at_k': hits / answerable_count if answerable_count else None,
        'mrr_at_k': reciprocal_ranks / answerable_count if answerable_count else None,
        'unanswerable_count': len(details) - answerable_count,
        'request_failure_count': request_failures,
        'reference_catalog_verification': [
            {'id': ref['id'], 'title': ref['title'], 'observed_matching_id_and_content': ref['id'] in observed_matching}
            for ref in dataset['reference_documents']],
        'cases': details,
    }


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--dataset', type=Path, default=ROOT / 'evaluation/business-scenarios-v1.json')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--timeout-seconds', type=int, default=180)
    args = parser.parse_args()
    parsed = urllib.parse.urlparse(args.base_url)
    if (parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost')
            or parsed.username or parsed.password or parsed.path not in ('', '/') or parsed.query or parsed.fragment):
        parser.error('Use a local HTTP service root URL on an isolated demo instance.')
    if args.output.exists():
        parser.error('Evidence output already exists; choose a new explicit output file.')
    if not 1 <= args.timeout_seconds <= 600:
        parser.error('timeout-seconds must be 1..600')
    dataset = json.loads(args.dataset.read_text(encoding='utf-8'))
    validate_dataset(dataset)
    knowledge_path = ROOT / 'demo/knowledge.json'
    demo = json.loads(knowledge_path.read_text(encoding='utf-8'))
    expected_demo = [{key: ref[key] for key in ('title', 'content')}
                     for ref in dataset['reference_documents'] if ref['origin'] == 'demo/knowledge.json']
    if demo['documents'] != expected_demo:
        parser.error('Demo knowledge changed since dataset was frozen; review references before running.')
    base = args.base_url.rstrip('/')
    suffix = str(time.time_ns())
    evidence = {'schema_version': 1, 'assessment_version': ASSESSMENT_VERSION,
                'mode': 'live_http_business_validation', 'synthetic_data': True,
                'started_at': utc_now(), 'dataset_id': dataset['dataset_id'],
                'dataset_sha256': hashlib.sha256(args.dataset.read_bytes()).hexdigest(),
                'knowledge_import_sha256': hashlib.sha256(knowledge_path.read_bytes()).hexdigest(),
                'reference_documents': dataset['reference_documents'],
                'chat_records': {}, 'retrieval_records': {}, 'structural_checks': {},
                'content_review_status': 'pending_semantic_review',
                'content_review': {case['id']: {'status': 'pending', 'criteria': case['content_criteria'],
                                              'reference_ids': case['reference_ids']}
                                   for case in dataset['chat_cases']},
                'limitations': dataset['limitations'] + [
                    '/search exposes no success/error or query-rewrite status; reranked=false alone is not failure.',
                    'intent_source_scores.llm, verified and grounded are model observations, never independent correctness labels.',
                    'Client-supplied user_id isolation is tested, not authentication or authorization.']}

    def save():
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    def request(path, data=None, method=None):
        body = None if data is None else json.dumps(data, ensure_ascii=False).encode('utf-8')
        req = urllib.request.Request(base + path, data=body, method=method,
                                     headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=args.timeout_seconds) as response:
            return json.load(response)

    def capture(path, payload=None, method=None):
        record = {'started_at': utc_now()}
        started = time.perf_counter()
        try:
            record['response'] = request(path, payload, method)
            if not isinstance(record['response'], dict):
                record['failure_type'] = 'InvalidResponseShape'
        except Exception as exc:
            # Do not serialize raw HTTP/provider errors, exception messages or headers.
            record['failure_type'] = type(exc).__name__
            if isinstance(exc, urllib.error.HTTPError):
                record['http_status'] = exc.code
        record['client_elapsed_ms'] = round((time.perf_counter() - started) * 1000, 3)
        return record

    try:
        evidence['health'] = request('/health')
        evidence['knowledge_before'] = request('/knowledge/stats')
        if evidence['knowledge_before'].get('total_chunks') != 6:
            raise ValueError('Fresh isolated service must contain exactly six default chunks before import')
        evidence['import'] = request('/knowledge/add', demo)
        if evidence['import'].get('added_chunks') != 2 or evidence['import'].get('total_chunks') != 8:
            raise ValueError('Expected exactly two synthetic demo chunks and eight total chunks')
    except Exception as exc:
        evidence['setup_failure_type'] = type(exc).__name__
        evidence['completed_at'] = utc_now()
        save()
        print(json.dumps({'phase': 'setup_failed', 'failure_type': type(exc).__name__}, ensure_ascii=False), flush=True)
        return 2
    save()
    # Capture retrieval first so a failed chat cannot hide the retrieval denominator.
    for case in dataset['retrieval_cases']:
        query = urllib.parse.urlencode({'query': case['query'], 'topK': case['top_k']})
        record = capture('/search?' + query, method='POST')
        record['query'] = case['query']
        evidence['retrieval_records'][case['id']] = record
        save()
        print(json.dumps({'phase': 'search', 'case': case['id'], 'failure_type': record.get('failure_type')}, ensure_ascii=False), flush=True)
    evidence['retrieval_metrics'] = assess_retrieval(dataset, evidence['retrieval_records'])
    save()
    for case in dataset['chat_cases']:
        payload = {'message': case['message'], 'user_id': 'business-' + case['user_key'] + '-' + suffix,
                   'conv_id': 'business-' + case['conversation_key'] + '-' + suffix}
        record = capture('/chat', payload)
        record['request'] = payload
        request_id = as_mapping(record.get('response')).get('request_id')
        if isinstance(request_id, str) and request_id:
            trace_record = capture('/trace/tool/' + urllib.parse.quote(request_id, safe=''))
            record['trace'] = trace_record.get('response', {})
            if 'failure_type' in trace_record:
                record['trace_failure_type'] = trace_record['failure_type']
        evidence['chat_records'][case['id']] = record
        evidence['structural_checks'][case['id']] = assess_chat(case, record, evidence['chat_records'])
        save()
        failed_checks = [item['name'] for item in evidence['structural_checks'][case['id']] if not item['passed']]
        print(json.dumps({'phase': 'chat', 'case': case['id'], 'failed_structural_checks': failed_checks}, ensure_ascii=False), flush=True)
    checks = [item for group in evidence['structural_checks'].values() for item in group]
    evidence['structural_summary'] = {'passed': sum(item['passed'] for item in checks), 'total': len(checks),
                                      'all_passed': all(item['passed'] for item in checks)}
    evidence['completed_at'] = utc_now()
    save()
    print(json.dumps({'evidence': str(args.output), 'structural_summary': evidence['structural_summary'],
                      'retrieval_hit_at_3': evidence['retrieval_metrics']['hit_at_k'],
                      'retrieval_mrr_at_3': evidence['retrieval_metrics']['mrr_at_k'],
                      'content_review_status': evidence['content_review_status']}, ensure_ascii=False), flush=True)
    return 0 if evidence['structural_summary']['all_passed'] and evidence['retrieval_metrics']['request_failure_count'] == 0 else 1


if __name__ == '__main__':
    raise SystemExit(main())
