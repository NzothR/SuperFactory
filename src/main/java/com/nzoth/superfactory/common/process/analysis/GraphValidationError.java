package com.nzoth.superfactory.common.process.analysis;

public final class GraphValidationError {

    public enum Severity {
        WARNING,
        ERROR
    }

    public final Severity severity;
    public final String code;
    public final String message;

    public GraphValidationError(Severity severity, String code, String message) {
        this.severity = severity;
        this.code = code == null ? "" : code;
        this.message = message == null ? "" : message;
    }

    @Override
    public String toString() {
        return severity + "[" + code + "] " + message;
    }
}
