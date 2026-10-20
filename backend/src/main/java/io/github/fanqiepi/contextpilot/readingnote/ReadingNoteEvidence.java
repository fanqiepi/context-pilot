package io.github.fanqiepi.contextpilot.readingnote;

import java.util.*;
import io.github.fanqiepi.contextpilot.book.*;
import io.github.fanqiepi.contextpilot.readingstate.ReadingScopeService;
import io.github.fanqiepi.contextpilot.readingtask.ReadingWork.Evidence;

/** Frozen evidence survives task removal; every read still validates immutable text and current scope. */
public final class ReadingNoteEvidence {
    private ReadingNoteEvidence() {}
    public static List<Evidence> read(String json) {
        return json==null?List.of():List.of(ReadingText.read(json,Evidence[].class));
    }
    public static List<String> ids(String json) { return read(json).stream().map(Evidence::id).toList(); }
    public static List<Evidence> retain(List<Evidence> all,List<String> keys) {
        ReadingText.require(keys!=null && keys.size()<=24 && keys.stream().allMatch(Objects::nonNull)
                && new HashSet<>(keys).size()==keys.size(),"引用选择不合法");
        ReadingText.require(all.stream().map(Evidence::id).toList().containsAll(keys),"引用只能从已有来源中删减");
        return all.stream().filter(e->keys.contains(e.id())).toList();
    }
    public static List<ReadingScopeService.Source> sources(Evidence e,ReadingScopeService scopes,
            ReadingScopeService.Snapshot scope,int requiredBoundary) {
        return e.sources().stream().map(span->{
            var source=scopes.source(scope,span.paragraphId(),span.startOffset(),span.endOffset(),requiredBoundary);
            if(!source.chapterId().equals(span.chapterId()) || source.paragraphOrdinal()!=span.paragraphOrdinal()
                    || !source.textHash().equals(span.textHash()) || !source.text().equals(span.text()))
                throw ReadingException.conflict("EVIDENCE_INVALID","原文引用校验失败");
            return source;
        }).toList();
    }
}
