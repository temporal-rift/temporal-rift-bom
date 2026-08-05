package io.github.temporalrift.asyncapi.codegen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

/** Parses an AsyncAPI 3 document and resolves both local and cross-file JSON References. */
final class AsyncApiDocument {

    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();
    private static final int MAX_REF_DEPTH = 10;

    private final JsonNode root;
    private final Path specFile;
    private final Map<Path, JsonNode> fileCache = new LinkedHashMap<>();

    private AsyncApiDocument(JsonNode root, Path specFile) {
        this.root = root;
        this.specFile = specFile;
        fileCache.put(specFile, root);
    }

    static AsyncApiDocument parse(Path specFile) throws IOException {
        JsonNode root = YAML_MAPPER.readTree(Files.newBufferedReader(specFile));
        return new AsyncApiDocument(root, specFile);
    }

    record Message(String name, JsonNode payloadSchema) {}

    record Channel(String key, String address, List<Message> messages) {}

    /** Every channel declared in the document, each with only its own messages, in declaration order. */
    List<Channel> channels() {
        JsonNode channels = root.path("channels");
        List<Channel> result = new ArrayList<>();
        var channelKeys = channels.fieldNames();
        while (channelKeys.hasNext()) {
            String channelKey = channelKeys.next();
            JsonNode channel = channels.path(channelKey);
            String address = channel.path("address").asText();
            result.add(new Channel(channelKey, address, messagesOf(channel)));
        }
        return result;
    }

    private List<Message> messagesOf(JsonNode channel) {
        JsonNode channelMessages = channel.path("messages");
        List<Message> messages = new ArrayList<>();
        var fieldNames = channelMessages.fieldNames();
        while (fieldNames.hasNext()) {
            String key = fieldNames.next();
            JsonNode messageRef = channelMessages.path(key);
            JsonNode message = resolve(messageRef, specFile);
            String name = message.path("name").asText(key);
            JsonNode payload = resolve(message.path("payload"), specFile);
            messages.add(new Message(name, payload));
        }
        return messages;
    }

    String title() {
        return root.path("info").path("title").asText("");
    }

    /** Resolves a possibly-$ref'd node against the file it was read from, following chained refs. */
    JsonNode resolve(JsonNode node, Path currentFile) {
        JsonNode current = node;
        Path currentFileRef = currentFile;
        int guard = 0;
        while (current.has("$ref")) {
            if (guard++ >= MAX_REF_DEPTH) {
                throw new IllegalStateException("Unresolvable or circular $ref chain starting in " + currentFile
                        + ", exceeded " + MAX_REF_DEPTH + " hops at " + current.path("$ref").asText());
            }
            String ref = current.path("$ref").asText();
            int hashIndex = ref.indexOf('#');
            String filePart = hashIndex < 0 ? ref : ref.substring(0, hashIndex);
            String fragment = hashIndex < 0 ? "" : ref.substring(hashIndex + 1);

            Path targetFile = filePart.isEmpty()
                    ? currentFileRef
                    : currentFileRef.toAbsolutePath().getParent().resolve(filePart).normalize();
            JsonNode targetRoot = fileCache.computeIfAbsent(targetFile, this::loadFile);
            current = navigateFragment(targetRoot, fragment);
            if (current.isMissingNode()) {
                throw new IllegalStateException("$ref \"" + ref + "\" in " + currentFileRef + " resolves to nothing");
            }
            currentFileRef = targetFile;
        }
        return current;
    }

    private JsonNode loadFile(Path file) {
        try {
            return YAML_MAPPER.readTree(Files.newBufferedReader(file));
        } catch (IOException e) {
            throw new UncheckedIOExceptionWrapper(e);
        }
    }

    private static JsonNode navigateFragment(JsonNode root, String fragment) {
        if (fragment.isEmpty()) {
            return root;
        }
        JsonNode current = root;
        for (String part : fragment.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            current = current.path(part);
        }
        return current;
    }

    /** The spec file this document was parsed from, for resolving refs relative to it. */
    Path specFile() {
        return specFile;
    }

    private static final class UncheckedIOExceptionWrapper extends RuntimeException {
        UncheckedIOExceptionWrapper(IOException cause) {
            super(cause);
        }
    }
}
