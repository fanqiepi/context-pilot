package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;

public sealed interface UpgradeToolArguments {
    record Search(String query,List<UUID> documentIds) implements UpgradeToolArguments { }
    record Read(String evidenceId) implements UpgradeToolArguments { }
    record Release(String tag) implements UpgradeToolArguments { }

    static UpgradeToolArguments parse(String name,String json,Set<UUID> selectedDocuments,Set<String> selectedTags) {
        if(json==null || json.length()>UpgradeBudget.TOOL_ARGUMENT_CHARACTERS) throw invalid();
        try {
            JsonNode object=UpgradeJson.MAPPER.readTree(json);
            if(object==null || !object.isObject()) throw invalid();
            return switch(name) {
                case "search_selected_documents" -> {
                    fields(object,Set.of("query","documentIds"));
                    String query=text(object,"query",500);
                    var ids=object.get("documentIds");
                    if(!ids.isArray() || ids.isEmpty() || ids.size()>3) throw invalid();
                    List<UUID> documents=new ArrayList<>();
                    for(var id:ids) {
                        if(!id.isTextual()) throw invalid();
                        UUID parsed=UUID.fromString(id.asText());
                        if(!selectedDocuments.contains(parsed)) throw new UpgradeToolException("TOOL_SCOPE_VIOLATION");
                        documents.add(parsed);
                    }
                    if(new HashSet<>(documents).size()!=documents.size()) throw invalid();
                    yield new Search(query.strip(),documents.stream().sorted().toList());
                }
                case "read_evidence" -> {fields(object,Set.of("evidenceId"));yield new Read(text(object,"evidenceId",40));}
                case "get_release_notes" -> {
                    fields(object,Set.of("tag"));String tag=text(object,"tag",100);
                    if(!tag.matches("[A-Za-z0-9._-]{1,100}") || tag.equals(".") || tag.equals("..") || !selectedTags.contains(tag)) throw new UpgradeToolException("TOOL_SCOPE_VIOLATION");
                    yield new Release(tag);
                }
                default -> throw new UpgradeToolException("UNKNOWN_TOOL");
            };
        } catch(UpgradeToolException error) {throw error;}
        catch(Exception error) {throw invalid();}
    }
    private static void fields(JsonNode node,Set<String> expected) {
        Set<String> actual=new HashSet<>();node.fieldNames().forEachRemaining(actual::add);
        if(!actual.equals(expected)) throw invalid();
    }
    private static String text(JsonNode node,String field,int max) {
        var value=node.get(field);
        if(value==null || !value.isTextual() || value.asText().isBlank() || value.asText().length()>max) throw invalid();
        return value.asText();
    }
    private static UpgradeToolException invalid() {return new UpgradeToolException("TOOL_ARGUMENT_INVALID");}
}
