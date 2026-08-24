package io.github.fanqiepi.contextpilot.chat;

import io.github.fanqiepi.contextpilot.memory.MemoryContext;

record ChatPrompt(String systemText, String baseUserText, String version, MemoryContext memoryContext) {

    ChatPrompt(String systemText, String baseUserText, String version) {
        this(systemText, baseUserText, version, MemoryContext.empty());
    }

    ChatPrompt withMemory(MemoryContext context) {
        return new ChatPrompt(systemText, baseUserText, version, context);
    }

    String userText() {
        if (memoryContext == null || memoryContext.isEmpty()) {
            return baseUserText;
        }
        String questionMarker = "\nQuestion:\n";
        int questionIndex = baseUserText.lastIndexOf(questionMarker);
        String preferences = "\n\n" + memoryContext.toPromptBlock();
        if (questionIndex < 0) {
            return baseUserText + preferences;
        }
        return baseUserText.substring(0, questionIndex)
                + preferences
                + baseUserText.substring(questionIndex);
    }
}
