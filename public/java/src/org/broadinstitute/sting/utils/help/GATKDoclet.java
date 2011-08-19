/*
 * Copyright (c) 2011, The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.utils.help;

import com.sun.javadoc.ClassDoc;
import com.sun.javadoc.RootDoc;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapper;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.broad.tribble.FeatureCodec;
import org.broadinstitute.sting.gatk.CommandLineGATK;
import org.broadinstitute.sting.gatk.walkers.qc.DocumentationTest;
import org.broadinstitute.sting.utils.exceptions.ReviewedStingException;
import org.broadinstitute.sting.utils.exceptions.UserException;

import java.io.*;
import java.util.*;

/**
 *
 */
public class GATKDoclet {
    final protected static File SETTINGS_DIR = new File("settings/helpTemplates");
    final protected static File DESTINATION_DIR = new File("gatkdocs");
    final protected static Logger logger = Logger.getLogger(GATKDoclet.class);
    protected static String buildTimestamp = null, absoluteVersion = null;
    protected static boolean showHiddenFeatures = false;
    protected static boolean testOnly = false;

    RootDoc rootDoc;

    final static Collection<DocumentedGATKFeatureObject> STATIC_DOCS = new ArrayList<DocumentedGATKFeatureObject>();
    static {
        STATIC_DOCS.add(new DocumentedGATKFeatureObject(FeatureCodec.class,
                "Reference ordered data (ROD) codecs",
                "Tribble codecs for reading reference ordered data such as VCF or BED files"));
    }


    /**
     * Extracts the contents of certain types of javadoc and adds them to an XML file.
     * @param rootDoc The documentation root.
     * @return Whether the JavaDoc run succeeded.
     * @throws java.io.IOException if output can't be written.
     */
    public static boolean start(RootDoc rootDoc) throws IOException {
        logger.setLevel(Level.DEBUG);
        // load arguments
        for(String[] options: rootDoc.options()) {
            if(options[0].equals("-build-timestamp"))
                buildTimestamp = options[1];
            if (options[0].equals("-absolute-version"))
                absoluteVersion = options[1];
            if (options[0].equals("-include-hidden"))
                showHiddenFeatures = true;
            if (options[0].equals("-test"))
                testOnly = true;
        }

        GATKDoclet doclet = new GATKDoclet();
        doclet.processDocs(rootDoc);
        return true;
    }

    /**
     * Validate the given options against options supported by this doclet.
     * @param option Option to validate.
     * @return Number of potential parameters; 0 if not supported.
     */
    public static int optionLength(String option) {
        if(option.equals("-build-timestamp") ||
                option.equals("-absolute-version") ||
                option.equals("-include-hidden")) {
            return 2;
        } else if ( option.equals("-test") )
            return 1;
        else
            return 0;
    }

    public boolean showHiddenFeatures() {
        return showHiddenFeatures;
    }

    public static boolean testOnly() {
        return testOnly;
    }

    private static final List<Class<?>> testOnlyKeepers = Arrays.asList(
            DocumentationTest.class, CommandLineGATK.class, UserException.class);
    public Set<GATKDocWorkUnit> workUnits() {
        TreeSet<GATKDocWorkUnit> m = new TreeSet<GATKDocWorkUnit>();

        for ( ClassDoc doc : rootDoc.classes() ) {
            //logger.debug("Considering " + doc);
            Class clazz = getClassForClassDoc(doc);

            // don't add anything that's not DocumentationTest if we are in test mode
            if ( clazz != null && testOnly && ! testOnlyKeepers.contains(clazz) )
                continue;

            //if ( clazz != null && clazz.getName().equals("org.broadinstitute.sting.gatk.walkers.annotator.AlleleBalance"))
            //    logger.debug("foo");

            DocumentedGATKFeatureObject feature = getFeatureForClassDoc(doc);
            DocumentedGATKFeatureHandler handler = createHandler(doc, feature);
            if ( handler != null && handler.includeInDocs(doc) ) {
                logger.info("Generating documentation for class " + doc);
                String filename = handler.getDestinationFilename(doc, clazz);
                GATKDocWorkUnit unit = new GATKDocWorkUnit(doc.name(),
                        filename, feature.groupName(),
                        feature, handler, doc, clazz,
                        buildTimestamp, absoluteVersion);
                m.add(unit);
            }
        }

        return m;
    }

    protected void processDocs(RootDoc rootDoc) {
        // setup the global access to the root
        this.rootDoc = rootDoc;

        try {
            // basic setup
            DESTINATION_DIR.mkdirs();
            FileUtils.copyFile(new File(SETTINGS_DIR + "/style.css"), new File(DESTINATION_DIR + "/style.css"));

            /* ------------------------------------------------------------------- */
            /* You should do this ONLY ONCE in the whole application life-cycle:   */

            Configuration cfg = new Configuration();
            // Specify the data source where the template files come from.
            cfg.setDirectoryForTemplateLoading(SETTINGS_DIR);
            // Specify how templates will see the data-model. This is an advanced topic...
            cfg.setObjectWrapper(new DefaultObjectWrapper());

            Set<GATKDocWorkUnit> myWorkUnits = workUnits();
            for ( GATKDocWorkUnit workUnit : myWorkUnits ) {
                processDocWorkUnit(cfg, workUnit, myWorkUnits);
            }

            processIndex(cfg, new ArrayList<GATKDocWorkUnit>(myWorkUnits));
        } catch ( FileNotFoundException e ) {
            throw new RuntimeException(e);
        } catch ( IOException e ) {
            throw new RuntimeException(e);
        }
    }

    private DocumentedGATKFeatureHandler createHandler(ClassDoc doc, DocumentedGATKFeatureObject feature) {
        if ( feature != null ) {
            if ( feature.enable() ) {
                DocumentedGATKFeatureHandler handler = new GenericDocumentationHandler();
                handler.setDoclet(this);
                return handler;
            } else {
                logger.info("Skipping disabled Documentation for " + doc);
            }
        }

        return null;
    }

    private DocumentedGATKFeatureObject getFeatureForClassDoc(ClassDoc doc) {
        Class<? extends Object> docClass = getClassForClassDoc(doc);

        if ( docClass == null )
            return null; // not annotated so it shouldn't be documented

        if ( docClass.isAnnotationPresent(DocumentedGATKFeature.class) ) {
            DocumentedGATKFeature f = docClass.getAnnotation(DocumentedGATKFeature.class);
            return new DocumentedGATKFeatureObject(docClass, f.enable(), f.groupName(), f.summary(), f.extraDocs());
        } else {
            for ( DocumentedGATKFeatureObject staticDocs : STATIC_DOCS ) {
                if ( staticDocs.getClassToDoc().isAssignableFrom(docClass) ) {
                    return new DocumentedGATKFeatureObject(docClass, staticDocs.enable(), staticDocs.groupName(), staticDocs.summary(), staticDocs.extraDocs());
                }
            }
            return null;
        }
    }

    private Class<? extends Object> getClassForClassDoc(ClassDoc doc) {
        try {
            // todo -- what do I need the ? extends Object to pass the compiler?
            return (Class<? extends Object>)HelpUtils.getClassForDoc(doc);
        } catch ( ClassNotFoundException e) {
            //logger.warn("Couldn't find class for ClassDoc " + doc);
            // we got a classdoc for a class we can't find.  Maybe in a library or something
            return null;
        } catch ( NoClassDefFoundError e ) {
            return null;
        } catch ( UnsatisfiedLinkError e) {
            return null; // naughty BWA bindings
        }
    }

    public static ClassDoc getClassDocForClass(RootDoc rootDoc, Class clazz) {
        return rootDoc.classNamed(clazz.getName());
    }

    private void processIndex(Configuration cfg, List<GATKDocWorkUnit> indexData) throws IOException {
        /* Get or create a template */
        Template temp = cfg.getTemplate("generic.index.template.html");

        /* Merge data-model with template */
        Writer out = new OutputStreamWriter(new FileOutputStream(new File(DESTINATION_DIR + "/index.html")));
        try {
            temp.process(groupIndexData(indexData), out);
            out.flush();
        } catch ( TemplateException e ) {
            throw new ReviewedStingException("Failed to create GATK documentation", e);
        }
    }

    private Map<String, Object> groupIndexData(List<GATKDocWorkUnit> indexData) {
        //
        // root -> data -> { summary -> y, filename -> z }, etc
        //      -> groups -> group1, group2, etc.
        Map<String, Object> root = new HashMap<String, Object>();

        Collections.sort(indexData);

        List<Map<String, String>> groups = new ArrayList<Map<String, String>>();
        Set<String> seenDocumentationFeatures = new HashSet<String>();
        List<Map<String, String>> data = new ArrayList<Map<String, String>>();
        for ( GATKDocWorkUnit workUnit : indexData ) {
            data.add(workUnit.indexDataMap());
            if ( ! seenDocumentationFeatures.contains(workUnit.annotation.groupName()) ) {
                groups.add(toMap(workUnit.annotation));
                seenDocumentationFeatures.add(workUnit.annotation.groupName());
            }
        }

        root.put("data", data);
        root.put("groups", groups);
        root.put("timestamp", buildTimestamp);
        root.put("version", absoluteVersion);

        return root;
    }

    private static final Map<String, String> toMap(DocumentedGATKFeatureObject annotation) {
        Map<String, String> root = new HashMap<String, String>();
        root.put("name", annotation.groupName());
        root.put("summary", annotation.summary());
        return root;
    }

    public final static GATKDocWorkUnit findWorkUnitForClass(Class c, Set<GATKDocWorkUnit> all) {
        for ( final GATKDocWorkUnit unit : all )
            if ( unit.clazz.equals(c) )
                return unit;
        return null;
    }

    private void processDocWorkUnit(Configuration cfg, GATKDocWorkUnit unit, Set<GATKDocWorkUnit> all)
            throws IOException {
        //System.out.printf("Processing documentation for class %s%n", unit.classDoc);

        unit.handler.processOne(rootDoc, unit, all);

        // Get or create a template
        Template temp = cfg.getTemplate(unit.handler.getTemplateName(unit.classDoc));

        // Merge data-model with template
        File outputPath = new File(DESTINATION_DIR + "/" + unit.filename);
        try {
            Writer out = new OutputStreamWriter(new FileOutputStream(outputPath));
            temp.process(unit.forTemplate, out);
            out.flush();
        } catch ( TemplateException e ) {
            throw new ReviewedStingException("Failed to create GATK documentation", e);
        }
    }
}
