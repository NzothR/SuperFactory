package com.nzoth.superfactory.common.process.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GraphValidationResult {

    private final List<GraphValidationError> entries = new ArrayList<>();

    public void warning(String code, String message) {
        entries.add(new GraphValidationError(GraphValidationError.Severity.WARNING, code, message));
    }

    public void error(String code, String message) {
        entries.add(new GraphValidationError(GraphValidationError.Severity.ERROR, code, message));
    }

    public boolean hasErrors() {
        for (GraphValidationError entry : entries) {
            if (entry.severity == GraphValidationError.Severity.ERROR) {
                return true;
            }
        }
        return false;
    }

    public int warningCount() {
        int count = 0;
        for (GraphValidationError entry : entries) {
            if (entry.severity == GraphValidationError.Severity.WARNING) {
                count++;
            }
        }
        return count;
    }

    public int errorCount() {
        int count = 0;
        for (GraphValidationError entry : entries) {
            if (entry.severity == GraphValidationError.Severity.ERROR) {
                count++;
            }
        }
        return count;
    }

    public List<GraphValidationError> entries() {
        return Collections.unmodifiableList(entries);
    }
}
