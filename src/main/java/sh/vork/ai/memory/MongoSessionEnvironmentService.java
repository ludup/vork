package sh.vork.ai.memory;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bson.Document;
import org.springframework.stereotype.Service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

@Service
public class MongoSessionEnvironmentService implements SessionEnvironmentService {

    private static final String SESSION_COLLECTION = "ai_session";

    private final MongoCollection<Document> sessionCollection;

    public MongoSessionEnvironmentService(MongoDatabase mongoDatabase) {
        this.sessionCollection = mongoDatabase.getCollection(SESSION_COLLECTION);
    }

    @Override
    public void setEnv(String sessionUuid, String key, String value) {
        if (sessionUuid == null || sessionUuid.isBlank() || key == null || key.isBlank()) {
            return;
        }
        String normalizedValue = value == null ? "" : value;
        sessionCollection.updateOne(
                Filters.eq("_id", sessionUuid),
                Updates.set("environmentVariables." + key, normalizedValue));
    }

    @Override
    public Map<String, String> getEnv(String sessionUuid) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return Map.of();
        }

        Document session = sessionCollection.find(Filters.eq("_id", sessionUuid)).first();
        if (session == null) {
            return Map.of();
        }

        Object rawEnv = session.get("environmentVariables");
        if (!(rawEnv instanceof Document envDoc)) {
            return Map.of();
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : envDoc.entrySet()) {
            if (entry.getKey() != null) {
                result.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
            }
        }
        return Map.copyOf(result);
    }
}
