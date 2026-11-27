"""vLLM's spawn-safe wrapper for the request-level OCR repeat filter."""

from vllm.v1.sample.logits_processor import AdapterLogitsProcessor

from handler import request_ngram_processor


class NoRepeatNGramAdapter(AdapterLogitsProcessor):
    def is_argmax_invariant(self) -> bool:
        # Banning a token can change the winner under greedy sampling.
        return False

    def new_req_logits_processor(self, params):
        return request_ngram_processor(params.extra_args)
