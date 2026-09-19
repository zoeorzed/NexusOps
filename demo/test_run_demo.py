"""Offline evidence-contract tests, not tests of model answer quality."""
import json
import unittest
from pathlib import Path
from run_demo import assess_evidence, SCENARIOS, MEMORY_ORDER


def structural_fixture():
    responses = {}
    for name in SCENARIOS:
        responses[name] = {
            'response': '订单' + MEMORY_ORDER,
            'conv_id': 'memory-demo',
            'agent_types': ['technical', 'billing'],
            'knowledge_used': True,
            'verified': True,
            'current_entities': {},
            'resolved_entities': {'order_id': [MEMORY_ORDER], 'amount': ['299元']},
        }
        responses[name + '_trace'] = {'trace': {'tool_calls': [
            {'tool_name': 'knowledge_search', 'success': True, 'fallback_used': False}]}}
    return {'responses': responses}


class EvidenceContractTest(unittest.TestCase):
    def test_complete_structural_fixture_passes_without_quality_claim(self):
        checks, observations = assess_evidence(structural_fixture())
        self.assertTrue(all(checks.values()))
        self.assertEqual(observations['memory_second']['answer_characters'], len('订单' + MEMORY_ORDER))
        self.assertNotIn('quality_passed', checks)

    def test_verifier_success_does_not_hide_failed_knowledge_tool(self):
        records = structural_fixture()
        records['responses']['memory_second_trace']['trace']['tool_calls'][0]['success'] = False
        checks, _ = assess_evidence(records)
        self.assertFalse(checks['memory_second_knowledge_trace_succeeded'])

    def test_response_fields_alone_do_not_prove_answer_uses_history(self):
        records = structural_fixture()
        records['responses']['memory_second']['response'] = '请提供订单号。'
        checks, _ = assess_evidence(records)
        self.assertTrue(checks['second_turn_order_comes_from_history'])
        self.assertFalse(checks['second_answer_quotes_order'])

    def test_missing_trace_and_empty_run_cannot_pass(self):
        records = structural_fixture()
        del records['responses']['memory_first_trace']
        checks, _ = assess_evidence(records)
        self.assertFalse(checks['memory_first_knowledge_trace_succeeded'])
        self.assertFalse(any(assess_evidence({})[0].values()))

    def test_repeat_order_in_current_turn_cannot_prove_memory(self):
        records = structural_fixture()
        records['responses']['memory_second']['current_entities'] = {'order_id': [MEMORY_ORDER]}
        checks, _ = assess_evidence(records)
        self.assertFalse(checks['second_turn_order_comes_from_history'])

    def test_demo_supplement_defers_to_default_refund_policy(self):
        payload = json.loads(Path(__file__).with_name('knowledge.json').read_text(encoding='utf-8'))
        billing = next(doc['content'] for doc in payload['documents'] if '重复扣款' in doc['title'])
        self.assertIn('默认《退款政策》', billing)
        self.assertIn('不规定核验反馈时限', billing)
        self.assertNotRegex(billing, r'\d+\s*(小时|个工作日|至|-)')
        self.assertIn('当前会话可以引用', billing)


if __name__ == '__main__':
    unittest.main()
