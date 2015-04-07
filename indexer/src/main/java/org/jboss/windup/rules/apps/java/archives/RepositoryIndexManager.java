package org.jboss.windup.rules.apps.java.archives;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.apache.commons.collections4.list.TreeList;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.SimpleFSDirectory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.Version;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.Indexer;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexUtils;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.creator.MinimalArtifactInfoIndexCreator;
import org.apache.maven.index.updater.IndexUpdateRequest;
import org.apache.maven.index.updater.IndexUpdateResult;
import org.apache.maven.index.updater.IndexUpdater;
import org.apache.maven.index.updater.ResourceFetcher;
import org.apache.maven.index.updater.WagonHelper;
import org.apache.maven.wagon.Wagon;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.jboss.forge.addon.dependencies.DependencyRepository;


/**
 * Downloads Maven index from given repository and produces a list of all artifacts, using this format: "SHA G:A:V[:C]".
 *
 * @author Ondrej Zizka, ozizka at redhat.com
 * @author <a href="mailto:lincolnbaxter@gmail.com">Lincoln Baxter, III</a>
 */
public class RepositoryIndexManager implements AutoCloseable
{
    private static final Logger log = Logger.getLogger(RepositoryIndexManager.class.getName());

    private File indexDirectory;

    private final PlexusContainer plexusContainer;
    private final Indexer indexer;
    private final IndexUpdater indexUpdater;
    private final Wagon httpWagon;
    private final IndexingContext context;

    private final File localCache;
    private final File indexDir;

    /**
     * Download the index for the given {@link DependencyRepository} and store the results at the specified output {@link File}
     * directory.
     */
    public static void generateMetadata(DependencyRepository repository, File indexDir, File outputDir) throws Exception
    {
        try (RepositoryIndexManager manager = new RepositoryIndexManager(indexDir, repository))
        {
            log.info("Downloading or updating index into " + indexDir.getPath());
            manager.downloadIndexAndUpdate();

            outputDir.mkdirs();
            final File metadataFile = getMetadataFile(repository, outputDir);
            try (FileWriter out = new FileWriter(metadataFile))
            {
                log.info("Writing sorted metadata to " + metadataFile.getPath());
                manager.writeMetadataTo(outputDir, out);
            }
        }
    }

    /**
     * Return <code>true</code> if metadata exists for the given {@link DependencyRepository} and output {@link File}
     * directory.
     */
    public static boolean metadataExists(DependencyRepository repository, File outputDir)
    {
        return getMetadataFile(repository, outputDir).exists();
    }

    /**
     * Get the metadata file for the given {@link DependencyRepository} and output {@link File} directory.
     */
    public static File getMetadataFile(DependencyRepository repository, File outputDir)
    {
        return new File(outputDir, repository.getId() + ".archive-metadata" + ".txt");
    }

    /*
     * Make it clear that this should not be instantiated.
     */
    private RepositoryIndexManager(File indexDirectory, DependencyRepository repository) throws PlexusContainerException,
                ComponentLookupException, IOException
    {
        final boolean updateExistingIndex = true;

        this.indexDirectory = indexDirectory;

        final DefaultContainerConfiguration config = new DefaultContainerConfiguration();
        config.setClassPathScanning(PlexusConstants.SCANNING_INDEX);
        this.plexusContainer = new DefaultPlexusContainer(config);

        this.indexer = plexusContainer.lookup(Indexer.class);
        this.indexUpdater = plexusContainer.lookup(IndexUpdater.class);
        this.httpWagon = plexusContainer.lookup(Wagon.class, "http");

        this.localCache = new File(this.indexDirectory, repository.getId() + "-cache");
        this.indexDir = new File(this.indexDirectory, repository.getId() + "-index");

        /*
         * https://maven.apache.org/maven-indexer/indexer-core/apidocs/index.html
         */
        List<IndexCreator> indexers = new ArrayList<>();
        indexers.add(plexusContainer.lookup(IndexCreator.class, MinimalArtifactInfoIndexCreator.ID));
        this.context = this.indexer.createIndexingContext(
            repository.getId() + "Context", repository.getId(),
            this.localCache, this.indexDir,
            repository.getUrl(), null, true, updateExistingIndex, indexers);
    }

    private void downloadIndexAndUpdate() throws IOException
    {
        ResourceFetcher resourceFetcher = new WagonHelper.WagonFetcher(httpWagon, new LoggingTransferListener(log), null, null);
        IndexUpdateRequest updateRequest = new IndexUpdateRequest(this.context, resourceFetcher);
        updateRequest.setIncrementalOnly(false);
        updateRequest.setForceFullUpdate(false);
        IndexUpdateResult updateResult = indexUpdater.fetchAndUpdateIndex(updateRequest);
        if (updateResult.isFullUpdate())
            log.info("Fully updated index for repository [" + this.context.getId() + "] - [" + this.context.getRepositoryUrl() + "]");
        else
            log.info("Incrementally updated index for repository [" + this.context.getId() + "] - [" + this.context.getRepositoryUrl() + "]");
    }

    /**
     * Prints all artifacts from the index, using format: SHA1 G:A:[P:[C:]]V.
     */
    private void writeMetadataTo(File outputDirectory, FileWriter writer) throws IOException
    {
        File outputLuceneDirectory = new File(outputDirectory, "lucene");
        outputLuceneDirectory.mkdirs();
        File markerFile = new File(outputLuceneDirectory, "archive-metadata.lucene.marker");
        markerFile.createNewFile();

        Directory output = new SimpleFSDirectory(outputLuceneDirectory);
        StandardAnalyzer standardAnalyzer = new StandardAnalyzer(Version.LUCENE_48);
        IndexWriterConfig config = new IndexWriterConfig(Version.LUCENE_48, standardAnalyzer);
        try (IndexWriter indexWriter = new IndexWriter(output, config))
        {
            final IndexSearcher searcher = this.context.acquireIndexSearcher();
            final IndexReader reader = searcher.getIndexReader();
            Bits liveDocs = MultiFields.getLiveDocs(reader);

            final String SKIPPED = "javadoc javadocs docs source sources test tests test-sources tests-sources test-javadoc tests-javadoc";
            Set<String> skippedClassifiers = new HashSet<>(Arrays.asList(SKIPPED.split(" ")));

            /*
             * Use a TreeList because it is faster to insert and sort.
             */
            List<String> lines = new TreeList<>();
            for (int i = 0; i < reader.maxDoc(); i++)
            {
                if (liveDocs == null || liveDocs.get(i))
                {
                    final Document doc = reader.document(i);
                    final ArtifactInfo info = IndexUtils.constructArtifactInfo(doc, this.context);

                    if (info == null)
                        continue;
                    if (info.getSha1() == null)
                        continue;
                    if (info.getSha1().length() != 40)
                        continue;
                    if ("tests".equals(info.getArtifactId()))
                        continue;
                    if ("pom".equals(info.getPackaging()))
                        continue;

                /*
                if ("javadoc".equals(info.getClassifier()))
                    continue;
                if ("javadocs".equals(info.getClassifier()))
                    continue;
                if ("docs".equals(info.getClassifier()))
                    continue;
                if ("source".equals(info.getClassifier()))
                    continue;
                if ("sources".equals(info.getClassifier()))
                    continue;
                if ("test".equals(info.getClassifier()))
                    continue;
                if ("tests".equals(info.getClassifier()))
                    continue;
                if ("test-sources".equals(info.getClassifier()))
                    continue;
                if ("tests-sources".equals(info.getClassifier()))
                    continue;
                if ("test-javadoc".equals(info.getClassifier()))
                    continue;
                if ("tests-javadoc".equals(info.getClassifier()))
                    continue;
                /**/

                    if (skippedClassifiers.contains(info.getClassifier()))
                        continue;

                    // G:A:[P:[C:]]V
                    // Unfortunately, G:A:::V leads to empty strings instead of nulls, see FORGE-2230.
                    StringBuilder line = new StringBuilder();

                    String sha1 = StringUtils.lowerCase(info.getSha1());
                    String packaging = StringUtils.defaultString(info.getPackaging());
                    String classifier = StringUtils.defaultString(info.getClassifier());

                    Document outputDoc = new Document();
                    outputDoc.add(new StringField("sha1", sha1, Field.Store.YES));
                    outputDoc.add(new StringField("groupId", info.getGroupId(), Field.Store.YES));
                    outputDoc.add(new StringField("artifactId", info.getArtifactId(), Field.Store.YES));
                    outputDoc.add(new StringField("packaging", packaging, Field.Store.YES));
                    outputDoc.add(new StringField("classifier", classifier, Field.Store.YES));
                    outputDoc.add(new StringField("version", info.getVersion(), Field.Store.YES));
                    indexWriter.addDocument(outputDoc);

                    line.append(sha1).append(' ');
                    line.append(info.getGroupId()).append(":");
                    line.append(info.getArtifactId()).append(":");
                    // if (info.getPackaging() != null)
                    line.append(packaging).append(":");
                    // if (info.getClassifier() != null)
                    line.append(classifier).append(":");
                    line.append(info.getVersion());

                    line.append("\n"); // System.lineSeparator() leads to system dependent build.

                    lines.add(line.toString());
                }

            }

            Collections.sort(lines);

            for (String line : lines)
            {
                writer.append(line);
            }
        }
        output.close();
    }

    @Override
    public void close() throws IOException
    {
        this.context.close(false);
        this.indexer.closeIndexingContext(this.context, false);
    }
}
