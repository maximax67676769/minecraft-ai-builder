package ru.aibuilder.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AiBuilderClient implements ClientModInitializer {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final String API_URL = "https://api.openai.com/v1/responses";
    private static final String MODEL = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-5.6-luna");
    private static final int MAX_COMMANDS = 300;
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    @Override
    public void onInitializeClient() {
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String request = extractBuildRequest(message);
            if (request == null) return true;

            startBuild(request);
            return false;
        });

        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            String request = extractBuildRequest(command);
            if (request == null) return true;

            startBuild(request);
            return false;
        });
    }

    private static String extractBuildRequest(String raw) {
        String s = raw.trim();
        String lower = s.toLowerCase(Locale.ROOT);

        String[] prefixes = {"построй ", "построй:", "build "};
        for (String prefix : prefixes) {
            if (lower.startsWith(prefix)) {
                String request = s.substring(prefix.length()).trim();
                return request.isEmpty() ? null : request;
            }
        }
        return null;
    }

    private static void startBuild(String description) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null) return;
        if (!BUSY.compareAndSet(false, true)) {
            mc.player.displayClientMessage(Component.literal(
                    "§cAI Builder уже строит. Подожди окончания текущей постройки."
            ), false);
            return;
        }

        mc.player.displayClientMessage(Component.literal(
                "§7AI Builder: §fпланирую «" + description + "»..."
        ), false);

        CompletableFuture.runAsync(() -> {
            try {
                String apiKey = System.getenv("OPENAI_API_KEY");
                if (apiKey == null || apiKey.isBlank()) {
                    throw new IllegalStateException(
                            "Не найден OPENAI_API_KEY. Создай API key и задай переменную окружения."
                    );
                }

                List<String> commands = requestCommands(apiKey, description);

                mc.execute(() -> {
                    if (mc.player == null || mc.getConnection() == null) return;

                    int sent = 0;
                    for (String command : commands) {
                        String cleaned = cleanCommand(command);
                        if (cleaned.isEmpty()) continue;

                        // Minecraft 26.3: ClientPacketListener.sendCommand(String)
                        mc.getConnection().sendCommand(cleaned);
                        sent++;
                    }

                    mc.player.displayClientMessage(Component.literal(
                            "§aAI Builder: отправлено команд — " + sent
                    ), false);
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                mc.execute(() -> {
                    if (mc.player != null) {
                        mc.player.displayClientMessage(Component.literal(
                                "§cAI Builder: " + message
                        ), false);
                    }
                });
            } finally {
                BUSY.set(false);
            }
        });
    }

    private static String cleanCommand(String command) {
        String s = command.trim();
        while (s.startsWith("/")) s = s.substring(1).trim();
        return s;
    }

    private static List<String> requestCommands(String apiKey, String description)
            throws IOException, InterruptedException {

        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.addProperty("store", false);

        JsonArray input = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", buildPrompt(description));
        input.add(user);
        body.add("input", input);

        JsonObject text = new JsonObject();
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_schema");
        format.addProperty("name", "minecraft_build");
        format.addProperty("strict", true);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject summary = new JsonObject();
        summary.addProperty("type", "string");
        properties.add("summary", summary);

        JsonObject commands = new JsonObject();
        commands.addProperty("type", "array");
        commands.addProperty("maxItems", MAX_COMMANDS);

        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        item.addProperty("maxLength", 1000);
        commands.add("items", item);

        properties.add("commands", commands);
        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("summary");
        required.add("commands");
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);

        format.add("schema", schema);
        text.add("format", format);
        body.add("text", text);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OpenAI API HTTP " + response.statusCode() + ": "
                    + compact(response.body(), 500));
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        String outputText = extractOutputText(root);
        if (outputText == null || outputText.isBlank()) {
            throw new IOException("OpenAI не вернул текстовый результат.");
        }

        JsonObject result = JsonParser.parseString(outputText).getAsJsonObject();
        JsonArray commandArray = result.getAsJsonArray("commands");

        List<String> commandsOut = new ArrayList<>();
        for (int i = 0; i < commandArray.size() && i < MAX_COMMANDS; i++) {
            String command = commandArray.get(i).getAsString().trim();
            if (!command.isEmpty()) commandsOut.add(command);
        }

        if (commandsOut.isEmpty()) {
            throw new IOException("AI не сгенерировал ни одной команды.");
        }

        return commandsOut;
    }

    private static String extractOutputText(JsonObject root) {
        if (!root.has("output")) return null;

        JsonArray output = root.getAsJsonArray("output");
        for (int i = 0; i < output.size(); i++) {
            JsonObject item = output.get(i).getAsJsonObject();
            if (!"message".equals(item.has("type") ? item.get("type").getAsString() : "")) continue;
            if (!item.has("content")) continue;

            JsonArray content = item.getAsJsonArray("content");
            for (int j = 0; j < content.size(); j++) {
                JsonObject part = content.get(j).getAsJsonObject();
                if ("output_text".equals(part.has("type") ? part.get("type").getAsString() : "")
                        && part.has("text")) {
                    return part.get("text").getAsString();
                }
            }
        }
        return null;
    }

    private static String buildPrompt(String description) {
        return """
                Ты — Minecraft builder для Java Edition 26.3.
                Твоя задача — превратить описание пользователя в список команд Minecraft,
                которые клиент может отправить серверу от имени игрока.

                Описание пользователя:
                %s

                КРИТИЧЕСКИЕ ПРАВИЛА:
                1. Генерируй ТОЛЬКО валидные команды Minecraft Java Edition 1.21.11.
                2. Команды выполняются от позиции игрока. Используй преимущественно относительные
                   координаты ~ ~ ~, чтобы постройка появилась рядом с игроком.
                3. Считай точку ~ ~ ~ ногами игрока и строй вокруг/перед ним.
                4. Для обычной архитектуры предпочитай /fill и /setblock, а не сотни /setblock.
                5. Не используй WorldEdit, модовые команды или неизвестные команды.
                6. Не создавай командные блоки, если пользователь явно их не просил.
                7. Если пользователь просит командные блоки, их можно создавать через /setblock
                   и задавать им Command/auto/условия современным синтаксисом 26.3.
                8. Не выдавай игроку предметы и не меняй игровой режим, если это не требуется
                   непосредственно описанием постройки.
                9. Не используй абсолютные координаты, потому что ты не знаешь координаты игрока.
                10. Постройка должна быть законченной и пригодной для исполнения в том порядке,
                    в котором команды перечислены.
                11. Не используй markdown, комментарии или пояснения внутри массива commands.
                12. Старайся уложиться максимум в 300 команд; сложные формы делай через fill.
                13. Учитывай существующий рельеф настолько, насколько это возможно без чтения мира.
                14. Если пользователь указал размер, соблюдай его.
                15. summary — короткое описание того, что будет построено.
                """.formatted(description);
    }

    private static String compact(String value, int max) {
        String s = value.replace('\n', ' ').replace('\r', ' ');
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
