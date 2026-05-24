package sh.vork.ai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

class MongoSessionEnvironmentServiceTest {

    private MongoDatabase mongoDatabase;
    private MongoCollection<Document> sessionCollection;
    private FindIterable<Document> findIterable;
    private MongoSessionEnvironmentService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mongoDatabase = org.mockito.Mockito.mock(MongoDatabase.class);
        sessionCollection = (MongoCollection<Document>) org.mockito.Mockito.mock(MongoCollection.class);
        findIterable = (FindIterable<Document>) org.mockito.Mockito.mock(FindIterable.class);

        when(mongoDatabase.getCollection("ai_session")).thenReturn(sessionCollection);
        service = new MongoSessionEnvironmentService(mongoDatabase);
    }

    @Test
    void setEnv_whenInputsValid_updatesEnvironmentVariableField() {
        service.setEnv("session-1", "activeTargetAnchor", "local");

        verify(sessionCollection).updateOne(
                Filters.eq("_id", "session-1"),
                Updates.set("environmentVariables.activeTargetAnchor", "local"));
    }

    @Test
    void setEnv_whenValueNull_persistsEmptyString() {
        service.setEnv("session-2", "selectedProfile", null);

        verify(sessionCollection).updateOne(
                Filters.eq("_id", "session-2"),
                Updates.set("environmentVariables.selectedProfile", ""));
    }

    @Test
    void setEnv_whenSessionOrKeyInvalid_noWriteOccurs() {
        service.setEnv(null, "k", "v");
        service.setEnv("", "k", "v");
        service.setEnv("   ", "k", "v");
        service.setEnv("session-3", null, "v");
        service.setEnv("session-3", "", "v");
        service.setEnv("session-3", "   ", "v");

        verify(sessionCollection, never()).updateOne(any(Bson.class), any(Bson.class));
    }

    @Test
    void getEnv_whenSessionUuidInvalid_returnsEmptyMapWithoutQuery() {
        assertEquals(Map.of(), service.getEnv(null));
        assertEquals(Map.of(), service.getEnv(""));
        assertEquals(Map.of(), service.getEnv("   "));

        verify(sessionCollection, never()).find(any(Bson.class));
    }

    @Test
    void getEnv_whenSessionNotFound_returnsEmptyMap() {
        when(sessionCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        Map<String, String> env = service.getEnv("missing-session");

        assertEquals(Map.of(), env);
    }

    @Test
    void getEnv_whenDocumentHasNoEnvironmentVariables_returnsEmptyMap() {
        Document session = new Document("_id", "session-4");
        when(sessionCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(session);

        Map<String, String> env = service.getEnv("session-4");

        assertEquals(Map.of(), env);
    }

    @Test
    void getEnv_whenEnvironmentVariablesPresent_convertsValuesToStringsAndReturnsImmutableMap() {
        Document envDoc = new Document()
                .append("activeTargetAnchor", "local")
                .append("attempt", 2)
                .append("optional", null);
        Document session = new Document("_id", "session-5")
                .append("environmentVariables", envDoc);

        when(sessionCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(session);

        Map<String, String> env = service.getEnv("session-5");

        assertEquals("local", env.get("activeTargetAnchor"));
        assertEquals("2", env.get("attempt"));
        assertEquals("", env.get("optional"));
        assertThrows(UnsupportedOperationException.class, () -> env.put("k", "v"));
    }
}
