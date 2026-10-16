package io.github.fanqiepi.contextpilot.upgraderesearch;

/** Approved v1 limits, independent of the V4 deterministic budget. */
public final class UpgradeBudget {
    private UpgradeBudget() { }
    public static final String VERSION = "upgrade-budget-v1";
    public static final int RUN_SECONDS = 180, MODEL_SECONDS = 60, HTTP_SECONDS = 10;
    public static final int MODEL_CALLS = 4, TOOL_REQUESTS = 8, RETRIEVAL_CALLS = 9, HTTP_CALLS = 3;
    public static final int INPUT_CHARACTERS = 40000, EVIDENCE_CHARACTERS = 24000, ANSWER_CHARACTERS = 3000;
    public static final int LOCAL_EVIDENCE = 18, RELEASE_EVIDENCE = 3, HTTP_MAX_BYTES = 262144;
    public static final int TOOL_ARGUMENT_CHARACTERS = 2000, TOOL_CALLS_PER_ROUND = 3;
}
