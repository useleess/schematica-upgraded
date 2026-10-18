package com.github.lunatrius.schematica.util;

public final class MessageException extends IllegalArgumentException {
    private final String key;
    private final Object[] arguments;

    public MessageException(String key, Object... arguments) {
        super(key + " " + java.util.Arrays.toString(arguments));
        this.key = key;
        this.arguments = arguments.clone();
    }

    public String key() { return key; }

    /** Why something failed, for chat: the message of the innermost cause, or its type when it has none. */
    public static String reason(Throwable error) {
        Throwable inner = error;
        while (inner.getCause() != null && inner.getCause() != inner) inner = inner.getCause();
        String message = inner.getMessage();
        return message == null || message.trim().isEmpty() ? inner.getClass().getSimpleName() : message;
    }
    public Object[] arguments() { return arguments.clone(); }
}
