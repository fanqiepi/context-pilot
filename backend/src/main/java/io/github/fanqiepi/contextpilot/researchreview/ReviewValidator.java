package io.github.fanqiepi.contextpilot.researchreview;

import java.util.*;
import java.util.regex.Pattern;
import static io.github.fanqiepi.contextpilot.researchreview.ReviewData.*;

final class ReviewValidator {
    static void snapshot(Snapshot s) {
        if (!bounded(s.answer(),4000) || s.evidence()==null || s.evidence().isEmpty() || s.evidence().size()>16
                || s.evidence().stream().anyMatch(e->e==null || !bounded(e.text(),12000))
                || s.evidence().stream().mapToInt(e->e.text().length()).sum()>12000) throw invalid();
        var docs=new HashSet<>(s.sources().stream().map(e->e.id()).toList());
        var ids=new HashSet<String>();
        for(var e:s.evidence()) if(!docs.contains(e.documentId()) || e.id()==null
                || !e.id().matches("E[1-9][0-9]*") || !ids.add(e.id())) throw invalid();
    }
    static Result citationIssues(Snapshot s) {
        var ids=new HashSet<>(s.evidence().stream().map(e->e.id()).toList());
        var matcher=Pattern.compile("\\[E[^\\]]*(?:\\]|$)").matcher(s.answer());
        var issues=new ArrayList<String>(); int count=0;
        while(matcher.find()) {
            count++; String value=matcher.group();
            if(!value.matches("\\[E[1-9][0-9]*\\]") || !ids.contains(value.substring(1,value.length()-1)))
                if(issues.size()<5) issues.add("原答案包含格式错误或不属于本轮证据的引用："+value.substring(0,Math.min(100,value.length())));
        }
        if(count==0) issues.add("原答案没有可核对的证据引用。");
        return issues.isEmpty()?null:new Result(List.of(),issues,"请重新核对结论与所选资料的引用关系。");
    }
    static Result result(String text,Snapshot s) {
        if(!bounded(text,16000)) throw invalid();
        Result r;
        try { r=read(text,Result.class); } catch(RuntimeException e) { throw invalid(); }
        if(r==null || r.claims()==null || r.claims().isEmpty() || r.claims().size()>12 || r.gaps()==null
                || r.gaps().size()>5 || r.suggestedSupplement()==null || r.suggestedSupplement().length()>2000
                || r.gaps().stream().anyMatch(g->!bounded(g,300))) throw invalid();
        var allowed=new HashSet<>(s.evidence().stream().map(e->e.id()).toList());
        var seen=new HashSet<String>();
        for(var c:r.claims()) {
            if(c==null || !bounded(c.claim(),400) || !s.answer().contains(c.claim()) || !seen.add(c.claim())
                    || c.verdict()==null || !bounded(c.explanation(),600) || c.evidenceIds()==null
                    || c.evidenceIds().size()>4 || !allowed.containsAll(c.evidenceIds())
                    || new HashSet<>(c.evidenceIds()).size()!=c.evidenceIds().size()
                    || c.verdict()!=Verdict.UNSUPPORTED && c.evidenceIds().isEmpty()) throw invalid();
        }
        if(write(r).length()>16000) throw invalid();
        return r;
    }
    static Overall overall(Result r) {
        if(r.claims().isEmpty() || r.claims().stream().anyMatch(c->c.verdict()==Verdict.UNSUPPORTED || c.verdict()==Verdict.CONTRADICTED)) return Overall.ISSUES_FOUND;
        if(!r.gaps().isEmpty() || r.claims().stream().anyMatch(c->c.verdict()==Verdict.PARTIAL)) return Overall.NEEDS_MORE_EVIDENCE;
        return Overall.NO_ISSUES_FOUND;
    }
    private static boolean bounded(String s,int n) { return s!=null && !s.isBlank() && s.length()<=n; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("REVIEW_RESPONSE_INVALID"); }
    private ReviewValidator() { }
}
