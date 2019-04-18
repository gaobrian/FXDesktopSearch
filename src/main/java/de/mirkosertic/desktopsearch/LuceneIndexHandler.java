/*
 * FreeDesktopSearch - A Search Engine for your Desktop
 * Copyright (C) 2013 Mirko Sertic
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, see <http://www.gnu.org/licenses/>.
 */
package de.mirkosertic.desktopsearch;

import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.EncoderException;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.codec.net.URLCodec;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.NamedList;
import org.apache.tika.utils.DateUtils;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

@Slf4j
class LuceneIndexHandler {

    private static final int NUMBER_OF_FRAGMENTS = 5;

    private final Map<String, String> facetFieldToTitle;
    private final Configuration configuration;
    private final PreviewProcessor previewProcessor;
    private final SolrEmbedded solrEmbedded;
    private final SolrClient solrClient;

    public LuceneIndexHandler(final Configuration aConfiguration, final PreviewProcessor aPreviewProcessor) throws IOException {
        previewProcessor = aPreviewProcessor;
        configuration = aConfiguration;
        facetFieldToTitle = new HashMap<>();
        facetFieldToTitle.put(IndexFields.LANGUAGE, "Language");
        facetFieldToTitle.put("attr_author", "Author");
        facetFieldToTitle.put("attr_last-modified-year", "Last modified");
        facetFieldToTitle.put("attr_" + IndexFields.EXTENSION, "File type");

        final var theIndexDirectory = new File(aConfiguration.getConfigDirectory(), "index");
        theIndexDirectory.mkdirs();

        solrEmbedded = new SolrEmbedded(new SolrEmbedded.Config(theIndexDirectory));
        solrClient = solrEmbedded.solrClient();
    }

    private String[] facetFields() {
        final var result = new String[facetFieldToTitle.size()];
        var i=0;
        for (final var field : facetFieldToTitle.keySet()) {
            result[i++] = field;
        }
        return result;
    }

    public void crawlingStarts() {
    }

    public void addToIndex(final String aLocationId, final Content aContent) throws IOException {

        final var theLanguage = aContent.getLanguage();

        final var theDocument = new SolrInputDocument();
        theDocument.setField(IndexFields.UNIQUEID, aContent.getFileName());
        theDocument.setField(IndexFields.LOCATIONID, aLocationId);
        theDocument.setField(IndexFields.CONTENTMD5, DigestUtils.md5Hex(aContent.getFileContent()));
        theDocument.setField(IndexFields.LOCATIONID, aLocationId);
        theDocument.setField(IndexFields.FILESIZE, Long.toString(aContent.getFileSize()));
        theDocument.setField(IndexFields.LASTMODIFIED, Long.toString(aContent.getLastModified()));
        theDocument.setField(IndexFields.LANGUAGE, theLanguage.name());

        aContent.getMetadata().forEach(theEntry -> {
            if (!StringUtils.isEmpty(theEntry.key)) {
                final var theValue = theEntry.value;
                if (theValue instanceof String) {
                    final var theStringValue = (String) theValue;
                    if (!StringUtils.isEmpty(theStringValue)) {
                        theDocument.setField("attr_" + theEntry.key, theStringValue);
                    }
                }
                if (theValue instanceof Date) {
                    final var theDateValue = (Date) theValue;
                    final var theCalendar = GregorianCalendar.getInstance(DateUtils.UTC, Locale.US);
                    theCalendar.setTime(theDateValue);

                    // Full-Path
                    {
                        final var thePathInfo = String.format(
                                "%04d/%02d/%02d",
                                theCalendar.get(Calendar.YEAR),
                                theCalendar.get(Calendar.MONTH) + 1,
                                theCalendar.get(Calendar.DAY_OF_MONTH));

                        theDocument.setField("attr_" + theEntry.key+"-year-month-day", thePathInfo);
                    }
                    // Year
                    {
                        final var thePathInfo = String.format(
                                "%04d",
                                theCalendar.get(Calendar.YEAR));

                        theDocument.setField("attr_" + theEntry.key+"-year", thePathInfo);
                    }
                    // Year-month
                    {
                        final var thePathInfo = String.format(
                                "%04d/%02d",
                                theCalendar.get(Calendar.YEAR),
                                theCalendar.get(Calendar.MONTH) + 1);

                        theDocument.setField("attr_" + theEntry.key+"-year-month", thePathInfo);
                    }

                }
            }
        });

        theDocument.setField(IndexFields.CONTENT, aContent.getFileContent());

        try {
            solrClient.add(theDocument);
        } catch (final Exception e) {
            throw new IOException(e);
        }
    }

    public void removeFromIndex(final String aFileName) throws IOException {
        try {
            solrClient.deleteById(aFileName);
        } catch (final Exception e) {
            throw new IOException(e);
        }
    }

    public void shutdown() {
        try {
            solrEmbedded.shutdown();
        } catch (final Exception e) {
            log.error("Error while closing IndexWriter", e);
        }
    }

    public UpdateCheckResult checkIfModified(final String aFilename, final long aLastModified) throws IOException {

        final Map<String, Object> theParams = new HashMap<>();
        theParams.put("q", IndexFields.UNIQUEID + ":" + ClientUtils.escapeQueryChars(aFilename));

        try {
            final var theQueryResponse = solrClient.query(new SearchMapParams(theParams));
            if (theQueryResponse.getResults() == null || theQueryResponse.getResults().isEmpty()) {
                // Nothing in Index, hence mark it as updated
                return UpdateCheckResult.UPDATED;
            }
            final var theDocument = theQueryResponse.getResults().get(0);

            final long theStoredLastModified = Long.valueOf((String) theDocument.getFieldValue(IndexFields.LASTMODIFIED));
            if (theStoredLastModified != aLastModified) {
                return UpdateCheckResult.UPDATED;
            }
            return UpdateCheckResult.UNMODIFIED;
        } catch (final Exception e) {
            throw new IOException(e);
        }
    }

    private String encode(final String aValue) {
        final var theURLCodec = new URLCodec();
        try {
            return theURLCodec.encode(aValue);
        } catch (final EncoderException e) {
            return null;
        }
    }

    private long indexSize() throws IOException, SolrServerException {
        final var q = new SolrQuery("*:*");
        q.setRows(0);  // don't actually request any data
        final var theResponse = solrClient.query(q);
        if (theResponse.getResults() != null) {
            return theResponse.getResults().getNumFound();
        }
        return 0;
    }

    public QueryResult performQuery(final String aQueryString, final String aBasePath, final Configuration aConfiguration, final Map<String, String> aDrilldownFields) {

        final Map<String, Object> theParams = new HashMap<>();
        theParams.put("defType", "google");
        theParams.put("q", aQueryString);
        theParams.put("fl", "*,score");
        theParams.put("rows", Integer.toString(configuration.getNumberOfSearchResults()));
        theParams.put("facet", "true");
        theParams.put("facet.field", facetFields());
        theParams.put("hl", "true");
        theParams.put("hl.method", "unified");
        theParams.put("hl.fl", IndexFields.CONTENT);
        theParams.put("hl.snippets", Integer.toString(NUMBER_OF_FRAGMENTS));
        theParams.put("hl.fragsize", "100");

        final List<QueryFilter> activeFilters = new ArrayList<>();

        if (aDrilldownFields != null) {
            final List<String> theFilters = new ArrayList<>();
            for (final var theField : aDrilldownFields.entrySet()) {
                theFilters.add(theField.getKey()+":"+ClientUtils.escapeQueryChars(theField.getValue()));
                activeFilters.add(new QueryFilter(facetFieldToTitle.get(theField.getKey()) + " : " + theField.getValue(), filterFacet(aBasePath, theField.getKey())));
            }
            if (!theFilters.isEmpty()) {
                theParams.put("fq", theFilters.toArray(new String[theFilters.size()]));
            }
        }

        if (aConfiguration.isShowSimilarDocuments()) {
            theParams.put("mlt", "true");
            theParams.put("mlt.count", "5");
            theParams.put("mlt.fl", IndexFields.CONTENT);
        }

        try {
            final var theStartTime = System.currentTimeMillis();
            final var theQueryResponse = solrClient.query(new SearchMapParams(theParams));

            final List<QueryResultDocument> theDocuments = new ArrayList<>();
            if (theQueryResponse.getResults() != null) {
                for (var i = 0; i < theQueryResponse.getResults().size(); i++) {
                    final var theSolrDocument = theQueryResponse.getResults().get(i);

                    final var theFileName = (String) theSolrDocument.getFieldValue(IndexFields.UNIQUEID);
                    final long theStoredLastModified = Long.valueOf((String) theSolrDocument.getFieldValue(IndexFields.LASTMODIFIED));

                    final var theNormalizedScore = (int) (
                            ((float) theSolrDocument.getFieldValue("score")) / theQueryResponse.getResults().getMaxScore() * 5);

                    final var theHighlight = new StringBuilder();
                    final var theHighlightPhrases = theQueryResponse.getHighlighting().get(theFileName);
                    if (theHighlightPhrases != null) {
                        final var theContentSpans = theHighlightPhrases.get(IndexFields.CONTENT);
                        if (theContentSpans != null) {
                            for (final var thePhrase : theContentSpans) {
                                if (theHighlight.length() > 0) {
                                    theHighlight.append(" ... ");
                                }
                                theHighlight.append(thePhrase.trim());
                            }
                        } else {
                            log.warn("No highligting for {}", theFileName);
                        }
                    }

                    final var theFileOnDisk = new File(theFileName);
                    if (theFileOnDisk.exists()) {

                        final var thePreviewAvailable = previewProcessor.previewAvailableFor(theFileOnDisk);

                        final var theDocument = new QueryResultDocument(i, theFileName, theHighlight.toString().trim(),
                                theStoredLastModified, theNormalizedScore, theFileName, thePreviewAvailable);

                        if (configuration.isShowSimilarDocuments()) {
                            final var theMoreLikeThisDocuments = theQueryResponse.getMoreLikeThis().get(theFileName);
                            if (theMoreLikeThisDocuments != null) {
                                for (final var theMLt : theMoreLikeThisDocuments) {
                                    theDocument.addSimilarFile(((String) theMLt.getFieldValue(IndexFields.UNIQUEID)));
                                }
                            }
                        }

                        theDocuments.add(theDocument);

                    } else {

                        // Document can be deleted, as it is no longer on the hard drive
                        solrClient.deleteById(theFileName);
                    }
                }
            }

            final var theIndexSize = indexSize();

            final var theDuration = System.currentTimeMillis() - theStartTime;

            final List<FacetDimension> theDimensions = new ArrayList<>();
            fillFacet(IndexFields.LANGUAGE, aBasePath, theQueryResponse, theDimensions, t -> SupportedLanguage.valueOf(t).toLocale().getDisplayName());
            fillFacet("attr_author", aBasePath, theQueryResponse, theDimensions, t -> t);
            fillFacet("attr_last-modified-year", aBasePath, theQueryResponse, theDimensions, t -> t);
            fillFacet("attr_" + IndexFields.EXTENSION, aBasePath, theQueryResponse, theDimensions, t -> t);

            return new QueryResult(StringEscapeUtils.escapeHtml(aQueryString), theDuration, theDocuments, theDimensions, theIndexSize, Lists.reverse(activeFilters));
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String filterFacet(final String aPath, final String aFieldName) {
        try {
            final var url = new URL(aPath);
            final var thePaths = StringUtils.split(url.getPath(), "/");
            final var theResult = new StringBuilder();
            theResult.append(thePaths[1]);
            if (thePaths.length > 2) {
                for (var i = 2; i < thePaths.length; i++) {
                    final var theFilter = thePaths[i];
                    if (!theFilter.startsWith(aFieldName + "%3D")) {
                        theResult.append("/");
                        theResult.append(theFilter);
                    }
                }
            }
            return new URL(url.getProtocol(), url.getHost(), url.getPort(), "/search/" + theResult.toString()).toString();
        } catch (final MalformedURLException e) {
            throw new IllegalStateException("Cannot happen", e);
        }
    }

    private void fillFacet(final String aFacetField, final String aBacklink, final QueryResponse aQueryResponse, final List<FacetDimension> aDimensions,
            final Function<String, String> aConverter) {
        final var theFacet = aQueryResponse.getFacetField(aFacetField);
        if (theFacet != null) {
            final List<Facet> theFacets = new ArrayList<>();
            for (final var theCount : theFacet.getValues()) {
                if (theCount.getCount() > 0) {
                    final var theName = theCount.getName().trim();
                    if (!theName.isEmpty()) {
                        theFacets.add(new Facet(aConverter.apply(theName), theCount.getCount(),
                                aBacklink + "/" + encode(
                                        FacetSearchUtils.encode(aFacetField, theCount.getName()))));
                    }
                }
            }
            // Facetting only makes sense if there is more than one facet
            if (theFacets.size() > 1) {
                aDimensions.add(new FacetDimension(facetFieldToTitle.get(aFacetField), theFacets));
            }
        }
    }

    public Suggestion[] findSuggestionTermsFor(final String aTerm) {

        final Map<String, Object> theParams = new HashMap<>();
        theParams.put("fxsuggest.enabled", "true");
        theParams.put("fxsuggest.q", aTerm);
        theParams.put("fxsuggest.slop", Integer.toString(configuration.getSuggestionSlop()));
        theParams.put("fxsuggest.inorder", Boolean.toString(configuration.isSuggestionInOrder()));
        theParams.put("fxsuggest.numbersuggest", Integer.toString(configuration.getNumberOfSuggestions()));

        try {
            final var theQueryResponse = solrClient.query(new SearchMapParams(theParams));

            final var theSuggestions = (NamedList) theQueryResponse.getResponse().get("fxsuggest");
            final List<Suggestion> theResult = new ArrayList<>();
            for (var i = 0; i<theSuggestions.size(); i++) {
                final var theEntry = (Map) theSuggestions.get(Integer.toString(i));
                final var theLabel = (String) theEntry.get("label");
                final var theValue = (String) theEntry.get("value");
                theResult.add(new Suggestion(theLabel, theValue));
            }

            return theResult.toArray(new Suggestion[theResult.size()]);

        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    public File getFileOnDiskForDocument(final String aUniqueID) {
        return new File(aUniqueID);
    }
}