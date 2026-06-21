package com.haoshenqi.permission;

import java.util.Locale;
import java.util.Map;
import run.halo.app.core.extension.content.Post;

public enum PostAccessPermission {

    PUBLIC,
    NORMAL,
    PRIVATE;

    public static final String ANNOTATION_KEY = "permission.haoshenqi.com/access";

    public static PostAccessPermission from(Post post) {
        if (post == null || post.getMetadata() == null) {
            return NORMAL;
        }
        Map<String, String> annotations = post.getMetadata().getAnnotations();
        if (annotations == null) {
            return NORMAL;
        }
        return from(annotations.get(ANNOTATION_KEY));
    }

    public static PostAccessPermission from(String value) {
        if (value == null || value.isBlank()) {
            return NORMAL;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "PUBLIC" -> PUBLIC;
            case "NORMAL" -> NORMAL;
            case "PRIVATE" -> PRIVATE;
            default -> NORMAL;
        };
    }
}
