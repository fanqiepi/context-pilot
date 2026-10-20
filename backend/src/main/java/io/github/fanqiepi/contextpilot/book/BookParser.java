package io.github.fanqiepi.contextpilot.book;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class BookParser {
    public enum Rule { TXT_CN_CHAPTER_V1, TXT_EN_CHAPTER_V1, MARKDOWN_ATX_V1, SINGLE_CHAPTER_V1 }
    public record Chapter(String title, List<String> paragraphs) {}
    public record Parsed(List<Chapter> chapters, int paragraphCount, int characterCount, int splitCount, String textHash) {}
    private static final Pattern CN = Pattern.compile("^第[〇零一二三四五六七八九十百千万两0-9]{1,20}[章回节].*$");
    private static final Pattern EN = Pattern.compile("(?i)^Chapter\\s+[0-9]{1,8}(?:\\s.*|[.:：].*)?$");

    public Rule rule(String value, Integer headingLevel) {
        Rule rule;
        try { rule = Rule.valueOf(value); }
        catch (RuntimeException error) { throw ReadingException.invalid("INVALID_PARSER", "请选择支持的分章规则"); }
        if (rule == Rule.MARKDOWN_ATX_V1) ReadingText.require(headingLevel != null && headingLevel >= 1 && headingLevel <= 3, "Markdown 标题级别应为 1–3");
        else ReadingText.require(headingLevel == null, "TXT/单章规则不接受标题级别");
        return rule;
    }

    public Parsed parse(byte[] bytes, Rule rule, Integer headingLevel) {
        if (bytes.length == 0) throw ReadingException.invalid("EMPTY_TEXT", "文件没有正文");
        if (bytes.length > 20 * 1024 * 1024) throw new ReadingException(413, "IMPORT_LIMIT_EXCEEDED", "文件超过 20 MiB");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) { throw ReadingException.invalid("INVALID_UTF8", "请选择 UTF-8 编码文件"); }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.indexOf('\0') >= 0) throw ReadingException.invalid("INVALID_TEXT", "正文不能包含 NUL 字符");
        if (text.isBlank()) throw ReadingException.invalid("EMPTY_TEXT", "文件没有正文");

        var chapters = new ArrayList<Chapter>();
        var paragraphs = new ArrayList<String>();
        var block = new StringBuilder();
        String title = rule == Rule.SINGLE_CHAPTER_V1 ? "正文" : "序章";
        boolean found = false, explicitChapter = false;
        char fence = 0;
        int fenceLength = 0, splits = 0;
        for (String line : text.split("\n", -1)) {
            if (Thread.currentThread().isInterrupted()) throw ReadingException.conflict("PARSE_INTERRUPTED", "解析已中断，可重新预览");
            String trimmed = line.strip();
            boolean markdown = rule == Rule.MARKDOWN_ATX_V1;
            String heading = null;
            if (fence == 0 && trimmed.length() <= 240) {
                if (rule == Rule.TXT_CN_CHAPTER_V1 && CN.matcher(trimmed).matches()) heading = trimmed;
                if (rule == Rule.TXT_EN_CHAPTER_V1 && EN.matcher(trimmed).matches()) heading = trimmed;
                if (markdown && line.matches(" {0,3}#{" + headingLevel + "}\\s+.+"))
                    heading = line.stripLeading().substring(headingLevel).strip();
            }
            if (heading != null) {
                splits += flush(block, paragraphs);
                if (explicitChapter && paragraphs.isEmpty()) throw ReadingException.invalid("EMPTY_CHAPTER", "存在空章节，请更换分章规则");
                if (!paragraphs.isEmpty()) chapters.add(new Chapter(title, List.copyOf(paragraphs)));
                paragraphs.clear();
                title = heading;
                explicitChapter = true;
                found = true;
            } else if (markdown) {
                int run = fenceRun(trimmed);
                if (fence == 0 && run >= 3) {
                    splits += flush(block, paragraphs);
                    fence = trimmed.charAt(0);
                    fenceLength = run;
                    block.append(line).append('\n');
                } else if (fence != 0) {
                    block.append(line).append('\n');
                    if (run >= fenceLength && trimmed.charAt(0) == fence && trimmed.substring(run).isBlank()) {
                        fence = 0;
                        splits += flush(block, paragraphs);
                    }
                } else if (line.isBlank()) splits += flush(block, paragraphs);
                else block.append(line).append('\n');
            } else {
                if (!line.isBlank()) splits += append(line, paragraphs);
            }
            if (chapters.size() > 5000 || paragraphs.size() > 200000) throw limit();
        }
        splits += flush(block, paragraphs);
        if (explicitChapter && paragraphs.isEmpty()) throw ReadingException.invalid("EMPTY_CHAPTER", "存在空章节，请更换分章规则");
        if (!paragraphs.isEmpty()) chapters.add(new Chapter(title, List.copyOf(paragraphs)));
        if (!found && rule != Rule.SINGLE_CHAPTER_V1) throw ReadingException.invalid("CHAPTER_RULE_NO_MATCH", "未找到章节，请选择另一规则或单章导入");
        int count = 0, chars = 0;
        for (var chapter : chapters) {
            int chapterChars = chapter.paragraphs().stream().mapToInt(ReadingText::length).sum();
            if (chapterChars > 200000) throw limit();
            chars += chapterChars;
            count += chapter.paragraphs().size();
        }
        if (chapters.isEmpty() || chars == 0) throw ReadingException.invalid("EMPTY_TEXT", "文件没有正文");
        if (chapters.size() > 5000 || count > 200000 || chars > 5000000) throw limit();
        return new Parsed(List.copyOf(chapters), count, chars, splits, ReadingText.hash(ReadingText.json(chapters)));
    }

    private int fenceRun(String line) {
        if (line.isEmpty() || (line.charAt(0) != '`' && line.charAt(0) != '~')) return 0;
        int count = 0;
        while (count < line.length() && line.charAt(count) == line.charAt(0)) count++;
        return count;
    }
    private int flush(StringBuilder block, List<String> paragraphs) {
        if (block.isEmpty()) return 0;
        String text = block.toString();
        if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
        block.setLength(0);
        return text.isBlank() ? 0 : append(text, paragraphs);
    }
    private int append(String text, List<String> paragraphs) {
        int length = ReadingText.length(text), pieces = 0;
        for (int start = 0; start < length; start += 4000) {
            paragraphs.add(ReadingText.slice(text, start, Math.min(length, start + 4000)));
            pieces++;
        }
        return Math.max(0, pieces - 1);
    }
    private ReadingException limit() { return ReadingException.invalid("IMPORT_LIMIT_EXCEEDED", "正文、章节或段落超过阅读容量限制"); }
}
