/*        __
 *        \ \
 *   _   _ \ \  ______
 *  | | | | > \(  __  )
 *  | |_| |/ ^ \| || |
 *  | ._,_/_/ \_\_||_|
 *  | |
 *  |_|
 * 
 * ----------------------------------------------------------------------------
 * "THE BEER-WARE LICENSE" (Revision 42):
 * <rob ∂ CLABS dot CC> wrote this file. As long as you retain this notice you
 * can do whatever you want with this stuff. If we meet some day, and you think
 * this stuff is worth it, you can buy me a beer in return.
 * ----------------------------------------------------------------------------
 */
package org.wikipedia.processing;

import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.util.Collector;
import org.wikipedia.citolytics.cpa.types.WikiDocument;
import org.wikipedia.citolytics.cpa.types.WikiSimResult;
import org.wikipedia.citolytics.cpa.utils.WikiSimStringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Document Processor
 *
 * TODO Strip from CPA to allow general usage (aka WikiFlink)
 *
 * Extracts links from Wikipedia documents, generates result records.
 */
public class DocumentProcessor extends RichFlatMapFunction<String, WikiSimResult> {
    public static final String INFOBOX_TAG = "{{Infobox";
    public static String seeAlsoTitle = "==see also==";
    public static String seeAlsoRegex = "(^|\\W)" + seeAlsoTitle + "$";
    public static int seeAlsoRegexFlags = Pattern.MULTILINE + Pattern.CASE_INSENSITIVE;

    private double[] alphas = new double[]{1.0};
    private boolean enableWiki2006 = false; // WikiDump of 2006 does not contain namespace tags
    private boolean enableInfoBoxRemoval = true;

    private static final int WIKI2006_ID_MATCH_GROUP = 2;
    private static final int WIKI2006_TITLE_MATCH_GROUP = 1;
    private static final int WIKI2006_TEXT_MATCH_GROUP = 3;

    private static final int DEFAULT_ID_MATCH_GROUP = 3;
    private static final int DEFAULT_TITLE_MATCH_GROUP = 1;
    private static final int DEFAULT_TEXT_MATCH_GROUP = 4;
    private static final int DEFAULT_NS_MATCH_GROUP = 2;

    private int idMatchGroup = DEFAULT_ID_MATCH_GROUP;
    private int titleMatchGroup = DEFAULT_TITLE_MATCH_GROUP;
    private int textMatchGroup = DEFAULT_TEXT_MATCH_GROUP;
    private int nsMatchGroup = DEFAULT_NS_MATCH_GROUP;

    @Override
    public void open(Configuration parameter) throws Exception {
        super.open(parameter);

        enableWiki2006 = parameter.getBoolean("wiki2006", true);
        if(enableWiki2006) {
            enableWiki2006();
        }

        enableInfoBoxRemoval = parameter.getBoolean("removeInfoBox", true);

        String[] arr = parameter.getString("alpha", "1.0").split(",");
        alphas = new double[arr.length];
        for (int i = 0; i < arr.length; i++) {
            alphas[i] = Double.parseDouble(arr[i]);
        }
    }

    public void enableWiki2006() {
        idMatchGroup = WIKI2006_ID_MATCH_GROUP;
        titleMatchGroup = WIKI2006_TITLE_MATCH_GROUP;
        textMatchGroup = WIKI2006_TEXT_MATCH_GROUP;
        nsMatchGroup = -1;
    }

    public DocumentProcessor enableInfoBoxRemoval() {
        enableInfoBoxRemoval = true;
        return this;
    }


    @Override
    public void flatMap(String content, Collector<WikiSimResult> out) {

        WikiDocument doc = processDoc(content);

        if (doc == null) return;

        doc.collectLinksAsResult(out, alphas);
    }

    public WikiDocument processDoc(String content) {
        return processDoc(content, false);
    }

    public WikiDocument processDoc(String content, boolean processSeeAlsoOnly) {
        if (getRedirectMatcher(content).find()) {
            return null; // search for redirect -> skip if found
        }

        // search for a page-xml entity
        Matcher m = getPageMatcher(content);

        if (!m.find()) {
            return null; // if the record does not contain parsable page-xml
        }

        // Create a WikiDocument object from the xml
        WikiDocument doc = new WikiDocument(this);
        doc.setId(Integer.parseInt(m.group(idMatchGroup)));
        doc.setTitle(WikiSimStringUtils.unescapeEntities(m.group(titleMatchGroup)));
        doc.setNS(nsMatchGroup >= 0 ? Integer.parseInt(m.group(nsMatchGroup)) : 0);

        // skip docs from namespaces other than
        if (doc.getNS() != 0) return null;

        doc.setText(WikiSimStringUtils.unescapeEntities(m.group(textMatchGroup)));

        if (processSeeAlsoOnly) {
            doc.setText(getSeeAlsoSection(doc.getText()));
        }

        return doc;
    }


    public static Matcher getRedirectMatcher(String content) {
        Pattern redirect = Pattern.compile("<redirect", Pattern.CASE_INSENSITIVE);
        return redirect.matcher(content);
    }

    public Matcher getPageMatcher(String content) {

        // search for a page-xml entity
        // needle: title, (ns), id, text
        Pattern pageRegex;

        if (enableWiki2006) {
            pageRegex = Pattern.compile("(?:<page>\\s+)(?:<title>)(.*?)(?:</title>)\\s+(?:<id>)(.*?)(?:</id>)(?:.*?)(?:<text.*?>)(.*?)(?:</text>)", Pattern.DOTALL);
        } else {
            pageRegex = Pattern.compile("(?:<page>\\s+)(?:<title>)(.*?)(?:</title>)\\s+(?:<ns>)(.*?)(?:</ns>)\\s+(?:<id>)(.*?)(?:</id>)(?:.*?)(?:<text.*?>)(.*?)(?:</text>)", Pattern.DOTALL);
        }

        return pageRegex.matcher(content);
    }


    /**
     * Removes various elements from text that are not needed for WikiSim.
     *
     * @param wikiText
     * @return Body text without inter-wiki links, info boxes and "See also" section
     */
    public String cleanText(String wikiText) {
        String text = wikiText;

        // strip "see also" section
        text = stripSeeAlsoSection(text);

        // Remove all inter-wiki links
        Pattern p2 = Pattern.compile("\\[\\[(\\w\\w\\w?|simple)(-[\\w-]*)?:(.*?)\\]\\]");
        text = p2.matcher(text).replaceAll("");

        // remove info box
        if (enableInfoBoxRemoval) {
            text = removeInfoBox(text);
        }

        return text;
    }

    /**
     * Extract text of "See Also" section
     *
     * @param wikiText
     * @return seeAlsoText
     */
    public static String getSeeAlsoSection(String wikiText) {
        int seeAlsoStart = -1;
        String seeAlsoText = "";

        Pattern seeAlsoPattern = Pattern.compile(seeAlsoRegex, seeAlsoRegexFlags);
        Matcher seeAlsoMatcher = seeAlsoPattern.matcher(wikiText);

        if (seeAlsoMatcher.find()) {
            seeAlsoStart = seeAlsoMatcher.start();
        }

        if (seeAlsoStart > 0) {
            int seeAlsoEnd = seeAlsoStart + seeAlsoTitle.length();
            int nextHeadlineStart = wikiText.substring(seeAlsoStart + seeAlsoTitle.length()).indexOf("==");

            if (nextHeadlineStart > 0) {
                seeAlsoText = wikiText.substring(seeAlsoStart, seeAlsoEnd + nextHeadlineStart);
            } else {
                seeAlsoText = wikiText.substring(seeAlsoStart);
            }
        }

        return seeAlsoText;
    }


    /**
     * Remove links of "See Also" section
     *
     * @param wikiText
     * @return wikiText without "See Also" links
     */
    public static String stripSeeAlsoSection(String wikiText) {
        int seeAlsoStart = -1;
        Pattern seeAlsoPattern = Pattern.compile(DocumentProcessor.seeAlsoRegex, DocumentProcessor.seeAlsoRegexFlags);
        Matcher seeAlsoMatcher = seeAlsoPattern.matcher(wikiText);

        if (seeAlsoMatcher.find()) {
            seeAlsoStart = seeAlsoMatcher.start();
        }

        // See also section exists
        if (seeAlsoStart > 0) {
            int seeAlsoEnd = seeAlsoStart + DocumentProcessor.seeAlsoTitle.length();
            int nextHeadlineStart = wikiText.substring(seeAlsoStart + DocumentProcessor.seeAlsoTitle.length()).indexOf("==");

            String strippedWikiText = wikiText.substring(0, seeAlsoStart);

            // Append content after see also section
            if (nextHeadlineStart > 0) {
                strippedWikiText += wikiText.substring(nextHeadlineStart + seeAlsoEnd);
            }

            return strippedWikiText;
        }

        return wikiText;
    }

    /**
     * Removes info boxes (Wikipedia templates) from documents. For template information see:
     *
     * https://en.wikipedia.org/wiki/Template:Infobox
     *
     * Example info box:
     *
     * {{Infobox ... }}
     *
     * @param wikiText Document content in WikiMarkup
     * @return Document content without info boxes
     */
    public String removeInfoBox(String wikiText) {

        int startPos = wikiText.indexOf(INFOBOX_TAG);
        while (startPos >= 0) {  // Check for multiple infox boxes
            int open = 0;
            char[] text = wikiText.substring(startPos + 2).toCharArray();
            int closePos = findClosing(text, 0, '{', '}');

            wikiText = wikiText.substring(0, startPos) + wikiText.substring(startPos + closePos);

            // Search again
            startPos = wikiText.indexOf(INFOBOX_TAG);
        }

        return wikiText;
    }

    private int findClosing(char[] text, int openPos, char open, char close) {
        int closePos = openPos;
        int counter = 1;
        while (counter > 0 && closePos < text.length - 1) { // Check if is closing not exists
            char c = text[++closePos];
            if (c == open) {
                counter++;
            } else if (c == close) {
                counter--;
            }
        }
        return closePos;
    }
}








