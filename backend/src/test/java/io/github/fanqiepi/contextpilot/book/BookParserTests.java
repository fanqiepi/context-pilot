package io.github.fanqiepi.contextpilot.book;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BookParserTests {
    private final BookParser parser=new BookParser();
    private BookParser.Parsed parse(String text,BookParser.Rule rule,Integer level) {
        return parser.parse(text.getBytes(StandardCharsets.UTF_8),rule,level);
    }
    @Test void preservesTextAndNormalizesOnlyBomAndLineEndings() {
        var parsed=parse("\uFEFF序言\r\n\r\n第一章 风起\r\n  阿青😀\r\n第二章 后来\r\n秘密",BookParser.Rule.TXT_CN_CHAPTER_V1,null);
        assertThat(parsed.chapters()).hasSize(3);
        assertThat(parsed.chapters().get(1).paragraphs()).containsExactly("  阿青😀");
        assertThat(parsed.paragraphCount()).isEqualTo(3);
        assertThat(parsed.textHash()).isEqualTo(parse("序言\n\n第一章 风起\n  阿青😀\n第二章 后来\n秘密",BookParser.Rule.TXT_CN_CHAPTER_V1,null).textHash());
    }
    @Test void markdownIgnoresFencedHeadingsAndNeverExecutesHtml() {
        var parsed=parse("# 第一章\n甲\n乙\n\n```md\n# 不是章节\n```\n\n<script>danger()</script>\n# 第二章\n丙",BookParser.Rule.MARKDOWN_ATX_V1,1);
        assertThat(parsed.chapters()).hasSize(2);
        assertThat(parsed.chapters().getFirst().paragraphs()).containsExactly("甲\n乙","```md\n# 不是章节\n```","<script>danger()</script>");
    }
    @Test void rejectsBadEncodingEmptyChaptersAndUnmatchedRules() {
        assertThatThrownBy(()->parser.parse(new byte[]{(byte)0xc3,0x28},BookParser.Rule.SINGLE_CHAPTER_V1,null))
                .isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("INVALID_UTF8"));
        assertThatThrownBy(()->parse("第一章 风\n第二章 雨\n正文",BookParser.Rule.TXT_CN_CHAPTER_V1,null))
                .isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("EMPTY_CHAPTER"));
        assertThatThrownBy(()->parse("普通正文",BookParser.Rule.TXT_CN_CHAPTER_V1,null))
                .isInstanceOfSatisfying(ReadingException.class,e->assertThat(e.code()).isEqualTo("CHAPTER_RULE_NO_MATCH"));
    }
    @Test void splitsAtCodePointsWithoutCorruptingSupplementaryCharacters() {
        var parsed=parse("😀".repeat(4001),BookParser.Rule.SINGLE_CHAPTER_V1,null);
        assertThat(parsed.splitCount()).isEqualTo(1);
        assertThat(parsed.chapters().getFirst().paragraphs()).hasSize(2);
        assertThat(ReadingText.length(parsed.chapters().getFirst().paragraphs().getFirst())).isEqualTo(4000);
        assertThat(parsed.chapters().getFirst().paragraphs().getLast()).isEqualTo("😀");
    }
    @Test void enforcesChapterAndFileBoundariesAndStrictRequestFields() {
        assertThat(parse("a".repeat(200000),BookParser.Rule.SINGLE_CHAPTER_V1,null).characterCount()).isEqualTo(200000);
        assertThatThrownBy(()->parse("a".repeat(200001),BookParser.Rule.SINGLE_CHAPTER_V1,null)).isInstanceOf(ReadingException.class);
        assertThatThrownBy(()->parser.parse(new byte[20*1024*1024+1],BookParser.Rule.SINGLE_CHAPTER_V1,null)).isInstanceOf(ReadingException.class);
        assertThatThrownBy(()->ReadingText.read("{\"previewHash\":\"x\",\"ownerId\":\"attacker\"}",ReadingController.Confirm.class)).isInstanceOf(ReadingException.class);
    }
}
