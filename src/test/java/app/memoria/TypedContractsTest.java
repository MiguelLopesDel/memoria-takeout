package app.memoria;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

// Architectural ratchet for RFC 0003 (typed contracts): endpoints crossing the HTTP seam
// should use records/DTOs, not Map<String, Object>. The allowlist below covers the endpoints
// that predate the rule and may ONLY shrink — migrating an endpoint means removing it here.
// A new endpoint using Map (or a stale entry after a migration) fails this test.
// Known limitation: ResponseEntity<?> erases the payload type, so those endpoints are
// tracked by the RFC 0003 checklist instead of this gate.
class TypedContractsTest {

    private static final Set<String> MAP_CONTRACT_ALLOWLIST = Set.of(
            "addToCollection",
            "backfillChannels",
            "backfillTimestamps",
            "calendar",
            "calendarDense",
            "cleanupFormatDuplicates",
            "cleanupMetadataFragments",
            "cleanupUndatedHtmlFragments",
            "collectionEvents",
            "collections",
            "compare",
            "createCollection",
            "createTag",
            "createTopic",
            "days",
            "deleteSavedFilter",
            "deleteTopic",
            "eventsByTag",
            "metricsOverview",
            "note",
            "patterns",
            "quality",
            "removeFromCollection",
            "saveFilter",
            "saveNote",
            "savedFilters",
            "search",
            "site",
            "tagEvent",
            "tags",
            "tagsForEvent",
            "topics",
            "untagEvent",
            "updateTopic",
            "youtube",
            "youtubeChannel",
            "youtubeVideos");

    @Test
    void newEndpointsUseTypedContracts() {
        Set<String> violations = new TreeSet<>();
        for (Method method : ApiController.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(GetMapping.class) && !method.isAnnotationPresent(PostMapping.class)) {
                continue;
            }
            boolean usesMap = mentionsMap(method.getGenericReturnType().getTypeName());
            for (Parameter parameter : method.getParameters()) {
                if (parameter.isAnnotationPresent(RequestBody.class)
                        && mentionsMap(parameter.getParameterizedType().getTypeName())) {
                    usesMap = true;
                }
            }
            if (usesMap) {
                violations.add(method.getName());
            }
        }
        assertEquals(
                new TreeSet<>(MAP_CONTRACT_ALLOWLIST),
                violations,
                "Endpoint contracts using Map<String, Object> changed. New endpoints must use "
                        + "records/DTOs (docs/rfcs/0003-typed-contracts.md); if you migrated an "
                        + "endpoint to a DTO, remove it from MAP_CONTRACT_ALLOWLIST (ratchet).");
    }

    private boolean mentionsMap(String typeName) {
        return typeName.contains("java.util.Map");
    }
}
