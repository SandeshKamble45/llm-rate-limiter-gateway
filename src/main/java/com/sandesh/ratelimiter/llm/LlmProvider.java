package com.sandesh.ratelimiter.llm;

import com.sandesh.ratelimiter.model.UsageMetadata;

public interface LlmProvider {

    LlmResponse complete(String prompt);

    record LlmResponse(
            String modelUsed,
            String completion,
            UsageMetadata usage) {

        /*
         * Backward-compatible constructor for existing tests/callers.
         *
         * The old third argument represented total tokens.
         * New code should use the UsageMetadata constructor.
         */
        public LlmResponse(
                String modelUsed,
                String completion,
                int tokensUsed) {

            this(
                    modelUsed,
                    completion,
                    new UsageMetadata(
                            tokensUsed,
                            0,
                            tokensUsed)
            );
        }
    }
}