"""Offline tests for evidence accounting; these do not judge live model answers."""
import copy
import json
import unittest
from pathlib import Path

from run_business_validation import assess_chat, assess_retrieval, validate_dataset


DATASET_PATH = Path(__file__).resolve().parents[1] / 'evaluation/business-scenarios-v1.json'


def dataset_fixture():
    return json.loads(DATASET_PATH.read_text(encoding='utf-8'))


def retrieval_fixture(dataset):
    refs = {item['id']: item for item in dataset['reference_documents']}
    return {
        case['id']: {'response': {'results': [copy.deepcopy(refs[rid])
                                            for rid in case['expected_reference_ids']]}}
        for case in dataset['retrieval_cases']
    }


def chat_fixture():
    return {
        'request': {'user_id': 'customer-a', 'conv_id': 'conversation-a'},
        'response': {'response': '这里是一条待语义复核的回答。', 'conv_id': 'conversation-a',
                     'request_id': 'request-a', 'agent_types': ['billing'],
                     'current_entities': {'order_id': [], 'amount': []},
                     'resolved_entities': {'order_id': [], 'amount': []},
                     'verified': True, 'intent_source_scores': {'llm': 0}},
        'trace': {'found': True, 'trace': {'request_id': 'request-a', 'user_id': 'customer-a',
                                        'conversation_id': 'conversation-a', 'tool_calls': [
            {'tool_name': 'knowledge_search', 'success': True, 'fallback_used': False}]}},
    }


def check_results(case, record, previous=None):
    return {item['name']: item['passed'] for item in assess_chat(case, record, previous or {})}


class BusinessDatasetContractTest(unittest.TestCase):
    def test_frozen_dataset_is_bounded_and_references_are_consistent(self):
        dataset = dataset_fixture()
        validate_dataset(dataset)
        self.assertEqual(len(dataset['chat_cases']), 12)
        self.assertEqual(len(dataset['retrieval_cases']), 10)
        self.assertEqual(sum(case['answerable'] for case in dataset['retrieval_cases']), 8)

    def test_changed_reference_content_is_rejected_before_network(self):
        dataset = dataset_fixture()
        dataset['reference_documents'][0]['content'] = '改写后的其他政策'
        with self.assertRaisesRegex(ValueError, 'Reference ID'):
            validate_dataset(dataset)

    def test_unknown_relevance_label_is_rejected(self):
        dataset = dataset_fixture()
        dataset['retrieval_cases'][0]['expected_reference_ids'] = ['invented-reference-id']
        with self.assertRaisesRegex(ValueError, 'Unknown reference'):
            validate_dataset(dataset)


class RetrievalAccountingTest(unittest.TestCase):
    def test_answerable_metrics_exclude_no_answer_cases(self):
        dataset = dataset_fixture()
        records = retrieval_fixture(dataset)
        # A no-answer query is allowed to retrieve something irrelevant; that is
        # a semantic review item, not a hit or miss in the answerable denominator.
        records['missing_invoice_terms']['response']['results'] = [dataset['reference_documents'][0]]
        metrics = assess_retrieval(dataset, records)
        self.assertEqual(metrics['answerable_count'], 8)
        self.assertEqual(metrics['unanswerable_count'], 2)
        self.assertEqual(metrics['hit_at_k'], 1.0)
        self.assertEqual(metrics['mrr_at_k'], 1.0)
        self.assertTrue(all(item['observed_matching_id_and_content']
                            for item in metrics['reference_catalog_verification']))
        no_answer = next(item for item in metrics['cases'] if item['case_id'] == 'missing_invoice_terms')
        self.assertIsNone(no_answer['rank_of_first_relevant'])
        self.assertEqual(no_answer['no_answer_content_review'], 'pending_semantic_review')

    def test_failed_and_missing_requests_remain_in_denominator(self):
        dataset = dataset_fixture()
        records = retrieval_fixture(dataset)
        records['refund_timing'] = {'failure_type': 'TimeoutError'}
        del records['order_status']
        metrics = assess_retrieval(dataset, records)
        self.assertEqual(metrics['answerable_count'], 8)
        self.assertEqual(metrics['request_failure_count'], 2)
        self.assertEqual(metrics['hit_at_k'], 6 / 8)
        self.assertEqual(metrics['mrr_at_k'], 6 / 8)

    def test_correct_id_with_wrong_original_text_cannot_count_as_hit(self):
        dataset = dataset_fixture()
        records = retrieval_fixture(dataset)
        tampered = records['refund_timing']['response']['results'][0]
        tampered['content'] = '与冻结退款政策不同的原文'
        metrics = assess_retrieval(dataset, records)
        self.assertEqual(metrics['hit_at_k'], 7 / 8)
        result = next(item for item in metrics['cases'] if item['case_id'] == 'refund_timing')
        self.assertEqual(result['reference_content_mismatches'], [tampered['id']])
        self.assertFalse(metrics['reference_catalog_verification'][0]['observed_matching_id_and_content'])

    def test_top_k_boundary_and_actual_relevant_rank(self):
        dataset = dataset_fixture()
        records = retrieval_fixture(dataset)
        refs = dataset['reference_documents']
        records['refund_timing']['response']['results'] = [refs[1], refs[2], refs[0]]
        records['order_status']['response']['results'] = [refs[0], refs[2], refs[3], refs[1]]
        metrics = assess_retrieval(dataset, records)
        self.assertEqual(metrics['hit_at_k'], 7 / 8)
        self.assertAlmostEqual(metrics['mrr_at_k'], (6 + 1 / 3) / 8)

    def test_fallback_document_cannot_be_counted_as_successful_retrieval(self):
        dataset = dataset_fixture()
        records = retrieval_fixture(dataset)
        records['refund_timing']['response']['results'].insert(0, {'id': 'fallback', 'metadata': {}})
        metrics = assess_retrieval(dataset, records)
        self.assertEqual(metrics['hit_at_k'], 7 / 8)
        self.assertTrue(metrics['cases'][0]['fallback_document_seen'])

    def test_mismatched_and_invalid_results_keep_their_actual_rank_positions(self):
        dataset = dataset_fixture()
        records = retrieval_fixture(dataset)
        correct = copy.deepcopy(dataset['reference_documents'][0])
        tampered = copy.deepcopy(correct)
        tampered['content'] = '错误的政策内容'
        records['refund_timing']['response']['results'] = [tampered, None, correct]
        metrics = assess_retrieval(dataset, records)
        self.assertEqual(metrics['cases'][0]['rank_of_first_relevant'], 3)
        self.assertEqual([item['position'] for item in metrics['cases'][0]['returned_positions']], [1, 2, 3])
        self.assertAlmostEqual(metrics['mrr_at_k'], (7 + 1 / 3) / 8)

    def test_duplicate_irrelevant_results_do_not_promote_later_hit(self):
        dataset = dataset_fixture()
        records = retrieval_fixture(dataset)
        refs = dataset['reference_documents']
        records['refund_timing']['response']['results'] = [refs[1], refs[1], refs[0]]
        metrics = assess_retrieval(dataset, records)
        self.assertEqual(metrics['cases'][0]['rank_of_first_relevant'], 3)
        self.assertEqual(metrics['cases'][0]['returned_ids'][:2], [refs[1]['id'], refs[1]['id']])

    def test_malformed_json_shapes_count_as_failed_requests_without_crashing(self):
        dataset = dataset_fixture()
        records = retrieval_fixture(dataset)
        records['refund_timing']['response'] = ['not-an-object']
        records['order_status']['response'] = {'results': None}
        metrics = assess_retrieval(dataset, records)
        self.assertEqual(metrics['request_failure_count'], 2)
        self.assertEqual(metrics['hit_at_k'], 6 / 8)


class ChatEvidenceContractTest(unittest.TestCase):
    def test_model_observations_do_not_decide_structural_or_semantic_correctness(self):
        case = {'expected_agents': ['billing']}
        record = chat_fixture()
        checks = check_results(case, record)
        self.assertTrue(all(checks.values()))
        record['response']['verified'] = False
        record['response']['intent_source_scores']['llm'] = 1
        self.assertEqual(checks, check_results(case, record))
        self.assertFalse(any('quality' in name or 'semantic' in name for name in checks))

    def test_verifier_cannot_hide_tool_failure_or_wrong_trace_identity(self):
        record = chat_fixture()
        record['trace']['trace']['request_id'] = 'different-request'
        record['trace']['trace']['tool_calls'][0]['success'] = False
        checks = check_results({}, record)
        self.assertFalse(checks['trace_request_matches'])
        self.assertFalse(checks['knowledge_trace_success_without_reported_fallback'])

    def test_missing_entity_field_does_not_prove_isolation(self):
        case = {'expected_resolved_absent': ['order_id']}
        record = chat_fixture()
        self.assertTrue(check_results(case, record)['resolved_entities_order_id_empty'])
        del record['response']['resolved_entities']['order_id']
        self.assertFalse(check_results(case, record)['resolved_entities_order_id_empty'])

    def test_history_resolution_requires_successful_same_conversation_seed(self):
        case = {'must_follow': 'seed', 'expected_current_absent': ['order_id'],
                'expected_resolved_entities': {'order_id': ['ORDER-1']}}
        record = chat_fixture()
        record['response']['resolved_entities']['order_id'] = ['ORDER-1']
        seed = chat_fixture()
        checks = check_results(case, record, {'seed': seed})
        self.assertTrue(all(checks.values()))
        seed['response']['conv_id'] = 'other-conversation'
        self.assertFalse(check_results(case, record, {'seed': seed})['same_conversation_as_seed'])
        self.assertFalse(check_results(case, record)['history_seed_completed'])

    def test_other_user_order_disclosure_is_a_failure_even_with_empty_entities(self):
        case = {'forbidden_answer_values': ['PRIVATE-ORDER']}
        record = chat_fixture()
        record['response']['response'] = '你的订单是 PRIVATE-ORDER。'
        self.assertFalse(check_results(case, record)['unconfirmed_order_not_disclosed_PRIVATE-ORDER'])

    def test_escalation_without_knowledge_trace_still_requires_trace_identity(self):
        case = {'expected_primary_agent': 'escalation', 'expected_escalated': True}
        record = chat_fixture()
        record['response'].update({'primary_agent': 'escalation', 'escalated': True})
        record['trace']['trace'].update({'escalated': True, 'tool_calls': []})
        self.assertTrue(all(check_results(case, record).values()))
        record['trace']['trace']['user_id'] = 'different-user'
        self.assertFalse(check_results(case, record)['trace_client_identifiers_match'])

    def test_reported_execution_timeout_fails_structural_check(self):
        record = chat_fixture()
        record['trace']['trace']['tool_calls'].append(
            {'tool_name': 'agent_execution:billing', 'success': False, 'error': 'timeout'})
        self.assertFalse(check_results({}, record)['no_reported_agent_execution_failure'])

    def test_malformed_response_trace_and_entity_fields_fail_without_crashing(self):
        case = {'expected_agents': ['billing'], 'expected_resolved_entities': {'order_id': ['ORDER-1']},
                'expected_current_absent': ['order_id']}
        record = {'response': [], 'trace': {'trace': []}}
        checks = check_results(case, record)
        self.assertFalse(checks['response_present'])
        self.assertFalse(checks['request_completed'])
        self.assertFalse(checks['resolved_entities_order_id_contains_expected'])
        self.assertFalse(checks['current_entities_order_id_empty'])


if __name__ == '__main__':
    unittest.main()
