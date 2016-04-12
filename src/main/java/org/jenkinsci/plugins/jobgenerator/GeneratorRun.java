/*
The MIT License

Copyright (c) 2012-2013, Sylvain Benner.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
 */

package org.jenkinsci.plugins.jobgenerator;

import hudson.Util;
import hudson.model.Build;
import hudson.model.BuildListener;
import hudson.model.ParameterValue;
import hudson.model.Result;
import hudson.model.TopLevelItem;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Cause;
import hudson.model.ParametersAction;
import hudson.model.Run;
import hudson.plugins.parameterizedtrigger.AbstractBuildParameterFactory;
import hudson.plugins.parameterizedtrigger.AbstractBuildParameters;
import hudson.plugins.parameterizedtrigger.AbstractBuildParameters.DontTriggerException;
import hudson.plugins.parameterizedtrigger.BlockableBuildTriggerConfig;
import hudson.plugins.parameterizedtrigger.BuildTrigger;
import hudson.plugins.parameterizedtrigger.BuildTriggerConfig;
import hudson.util.XStream2;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

import jenkins.model.Jenkins;

import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.Text;
import org.dom4j.Visitor;
import org.dom4j.VisitorSupport;
import org.dom4j.io.SAXReader;
import org.jenkins_ci.plugins.run_condition.RunCondition;
import org.jenkinsci.plugins.jobgenerator.actions.*;
import org.jenkinsci.plugins.jobgenerator.parameters.*;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * Generates a configured job by copying this job config.xml and replacing
 * generator parameters with values provided by the user at build time.
 *
 * @author <a href="mailto:sylvain.benner@gmail.com">Sylvain Benner</a>
 */
@SuppressWarnings("rawtypes")
public class GeneratorRun extends Build<JobGenerator, GeneratorRun> {

    private static final Logger LOGGER = Logger.getLogger(
                                                 GeneratorRun.class.getName());
    private static final char[] specialChars = {'\\', '/', ':', '*', '"', '<',
                                                '>', '|'};
    private List<DownstreamGenerator> downstreamGenerators =
                                         new ArrayList<DownstreamGenerator>();
    private enum ReplaceType{
        NORMAL, SPECIAL_CHARS, REG_EXP;
    }
    
    private class DownstreamGenerator{
        private final AbstractProject job;
        private final List<List<ParametersAction>> importParams;
        private final Element node;

        public DownstreamGenerator(
                AbstractProject job,
                List<List<ParametersAction>> params,
                Element node){
           this.job = job;
           this.importParams = params;
           this.node = node;
       }
    }

    public GeneratorRun(JobGenerator job, File buildDir)
            throws IOException {
        super(job, buildDir);
    }

    public GeneratorRun(JobGenerator job) throws IOException {
        super(job);
    }

    public JobGenerator getJobGenerator() {
        return project;
    }

    public static String expand(String s, List<ParametersAction> params) {
        // check existenz of variables to replace
        for (ParametersAction p : params) {
            List<ParameterValue> values = p.getParameters();
            ParameterValue value = null;
            ReplaceType replType = ReplaceType.NORMAL;
            for (ParameterValue v : values) {
                String decorated = "${" + v.getName() + "}";
                if (s.contains(decorated)) {
                    value = v;
                    break;
                }
                decorated =  "${" + v.getName() + "*}";
                if (s.contains(decorated)) {
                    replType = ReplaceType.SPECIAL_CHARS;
                    value = v;
                    break;
                }
                Pattern pattern = Pattern.compile(
                        ".*?\\$\\{" + v.getName() + "/(.*?)/(.*?)\\}.*");
                Matcher matcher = pattern.matcher(s);
                if (matcher.find()) {
                    replType = ReplaceType.REG_EXP;
                    value = v;
                    break;
                }
            }
            if (value != null){
                s = GeneratorRun.expand(
                        s, value.getName(),
                        ((GeneratorKeyValueParameterValue) value).value,
                        replType);
                // replace nested variables
                s = expand(s, params);
            }
        }
        return s;
    }

    private static String expand(String s, String n, String v, ReplaceType r){
        if(r == ReplaceType.NORMAL) {
            String decorated = "${" + n + "}";
            while (s.contains(decorated)) {
                s = s.replace(decorated, v);
            }
        }
        else if(r == ReplaceType.SPECIAL_CHARS) {
            for(char c: GeneratorRun.specialChars){
                v = v.replace(c, '_');
            }
            String decorated = "${" + n + "*}";
            while (s.contains(decorated)) {
                s = s.replace(decorated, v);
            }
        }
        else if (r == ReplaceType.REG_EXP){
            // fetch regexps and expression to replace in s
            Pattern pattern = Pattern.compile(
                    ".*?\\$\\{" + n + "/(.+?)/(.*?)\\}.*");
            Matcher regexps = pattern.matcher(s);
            if (regexps.find()){
                pattern = Pattern.compile(
                        ".*?(\\$\\{" + n + "/.*?/.*?\\}).*");
                Matcher toReplace = pattern.matcher(s);
                if (toReplace.find()) {
                    // apply regexp replacement to value v 
                    pattern = Pattern.compile(regexps.group(1));
                    Matcher vmatcher = pattern.matcher(v);
                    if (vmatcher.find()) {
                        v = vmatcher.replaceAll(regexps.group(2));
                    }
                    // finally replace the whole expression by the new value
                    s = s.replace(toReplace.group(1), v);
                }
            }
        }
        return s;
    }

    public static String getExpandedJobName(JobGenerator p,
                                            List<ParametersAction> params){
        String n = expand(p.getGeneratedJobName(), params);
        // force replacement of special characters
        for(char c: GeneratorRun.specialChars){
            n = n.replace(c, '_');
        }
        return n;
    }

    public static boolean allParametersAreResolved(Element root){
        List<String> enames = new ArrayList<String>();
        enames.add("arg1");
        enames.add("arg2");
        enames.add("expression");
        enames.add("label");
        for(String s: enames){
            Element e = (Element) root.selectSingleNode("/" + root.getName() +
                                                        "/*/" + s);
            if (e != null){
                String t = e.getText();
                if (t.contains("${") && t.contains("}")){
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean isEvaluationSupported(Element root){
        // TODO need to find a better way to detect if we can evaluate the
        // expression for a given conditional class
        List<String> notSupportedClasses = new ArrayList<String>();
        notSupportedClasses.add("CauseCondition");
        notSupportedClasses.add("StatusCondition");
        notSupportedClasses.add("DayCondition");
        notSupportedClasses.add("ShellCondition");
        notSupportedClasses.add("BatchFileCondition");
        notSupportedClasses.add("FileExistsCondition");
        notSupportedClasses.add("FilesMatchCondition");
        notSupportedClasses.add("TimeCondition");
        if(root.attribute("class") != null){
            String name = root.attributeValue("class");
            for(String nname: notSupportedClasses){
                if(name.contains(nname)){
                    return false;
                }
            }
        }
        List children = root.elements();
        for (Iterator i = children.iterator(); i.hasNext();) {
            Element e = (Element) i.next();
            return isEvaluationSupported(e);
        }
        return true;
    }

    public String id(Run run) throws UnsupportedEncodingException {
        return URLEncoder.encode(run.getParent().getFullDisplayName()
                                         + run.getNumber(),
                                 "UTF-8");
    }

    @SuppressWarnings("unchecked")
    @Override
    public void run() {
        this.execute(new GeneratorImpl());
    }

    protected class GeneratorImpl extends AbstractBuildExecution {

        public GeneratorImpl() {
        }

        protected Result doRun(BuildListener listener) throws Exception {
            // TODO syl20bnr: This function is a big mess. I plan to
            // refactor it for testing purpose.
            if(!this.checkParameters(listener)){
                return Result.FAILURE;
            }
            JobGenerator job = getJobGenerator();
            List<ParametersAction> params = getBuild().getActions(
                                          hudson.model.ParametersAction.class);
            String expName = getExpandedJobName(job, params);
            if(job.getDelete()){
                List<String> jobs = new ArrayList<String>();
                this.deleteJobs(job, !job.getProcessThisJobOnly(), jobs);
                // save deleted job name
                DeletedJobBuildAction action = new DeletedJobBuildAction(jobs);
                getBuild().addAction(action);
            }
            else{
                String expDispName = expand(
                        job.getGeneratedDisplayJobName(), params);
                File d = new File(job.getRootDir() +
                                  File.separator + ".." + File.separator +
                                  expName);
                if (!d.exists() && !d.mkdir()) {
                    return Result.FAILURE;
                }
                SAXReader reader = new SAXReader();
                Document doc = reader.read(
                                  job.getConfigFile().getFile());
                // Update root element
                Element root = doc.getRootElement();
                root.setName("project");
                root.remove(root.attribute("plugin"));
                // Update Display Name
                Node dispName = doc.selectSingleNode("//displayName");
                if(!expDispName.isEmpty()){
                    if(dispName != null){
                        dispName.setText(expDispName);
                    }
                    else{
                        root.addElement("displayName").addText(expDispName);
                    }
                }
                // Expand Vars
                Visitor v = new ExpandVarsVisitor(params, job.getDisableJobs());
                doc.accept(v);
                // Evaluate builders conditional blocks (Single step)
                List vroots = doc.selectNodes("//org.jenkinsci.plugins." +
                   "conditionalbuildstep.singlestep.SingleConditionalBuilder");
                for (Iterator i = vroots.iterator(); i.hasNext();) {
                    Element vroot = (Element) i.next();
                    v = new EvaluateBuildersSingleVisitor(vroot,
                                               (AbstractBuild<?, ?>)getBuild(),
                                               listener);
                    vroot.accept(v);
                    for (Element e: ((EvaluateBuildersSingleVisitor)v).toAdd){
                        List siblings = vroot.getParent().elements();
                        siblings.add(siblings.indexOf(vroot), e);
                    }
                    for (Element e:
                         ((EvaluateBuildersSingleVisitor)v).toRemove) {
                        e.detach();
                    }
                }
                // Evaluate builders conditional blocks (Multiple steps)
                vroots = doc.selectNodes("//org.jenkinsci.plugins." +
                                    "conditionalbuildstep.ConditionalBuilder");
                for (Iterator i = vroots.iterator(); i.hasNext();) {
                    Element vroot = (Element) i.next();
                    v = new EvaluateBuildersMultiVisitor(vroot,
                                               (AbstractBuild<?, ?>)getBuild(),
                                               listener);
                    vroot.accept(v);
                    for (Element e: ((EvaluateBuildersMultiVisitor)v).toAdd){
                        List siblings = vroot.getParent().elements();
                        siblings.add(siblings.indexOf(vroot), e);
                    }
                    for (Element e:
                         ((EvaluateBuildersMultiVisitor)v).toRemove) {
                        e.detach();
                    }
                }
                // Evaluate publishers conditional blocks
                Element flexroot = (Element) doc.selectSingleNode(
                                "//org.jenkins__ci.plugins." +
                                "flexible__publish.FlexiblePublisher");
                if(flexroot != null){
                    vroots = doc.selectNodes("//org.jenkins__ci.plugins." +
                                     "flexible__publish.ConditionalPublisher");
                    for (Iterator i = vroots.iterator(); i.hasNext();) {
                        Element vroot = (Element) i.next();
                        v = new EvaluatePublishersVisitor(vroot,
                                               (AbstractBuild<?, ?>)getBuild(),
                                               listener);
                        vroot.accept(v);
                        for (Element e: ((EvaluatePublishersVisitor)v).toAdd){
                            List siblings = flexroot.getParent().elements();
                            siblings.add(siblings.indexOf(flexroot), e);
                        }
                        for (Element e:
                             ((EvaluatePublishersVisitor)v).toRemove) {
                            e.detach();
                        }
                    }
                    this.removeNodeIfNoChild(flexroot, "publishers");
                    this.removeNodeIfNoChild(doc, "org.jenkins__ci.plugins." +
                                        "flexible__publish.FlexiblePublisher");
                }
                // gather all downstream jobs with their associated generator
                // parameters
                this.gatherDownstreamJobsFromBuildSteps(doc);
                this.gatherDownstreamJobsFromPublishers(doc);
                // resolve downstream job names
                this.resolveDownstreamJobNames();
                // Final step is to strip all info specific to Job Generator
                v = new GatherElementsToRemoveVisitor();
                doc.accept(v);
                for(Element e: ((GatherElementsToRemoveVisitor)v).toRemove){
                    e.detach();
                }
                this.removeNodeIfNoChild(doc, "parameterDefinitions");
                this.removeNodeIfNoChild(doc,
                                  "hudson.model.ParametersDefinitionProperty");
                this.removeNodeIfNoChild(doc, "generatedJobName");
                this.removeNodeIfNoChild(doc, "generatedDisplayJobName");
                this.removeNodeIfNoChild(doc, "autoRunJob");
                // Create/Update Job
                doc.normalize();
                InputStream is = new ByteArrayInputStream(
                                                doc.asXML().getBytes("UTF-8"));
//                System.out.println(doc.asXML());
                AbstractProject item = 
                        (AbstractProject) Jenkins.getInstance().getItem(expName);
                if(item != null){
                    StreamSource ss = new StreamSource(is);
                    item.updateByXml((Source)ss);
                    LOGGER.info(String.format("Updated configuration of " +
                                              "job %s", expName));
                }
                else{
                    item = (AbstractProject)
                            Jenkins.getInstance().createProjectFromXML(
                                                                  expName, is);
                    LOGGER.info(String.format("Created job %s", expName));
                }
                // save generated job name
                GeneratedJobBuildAction action =
                              new GeneratedJobBuildAction(expName, item!=null);
                getBuild().addAction(action);
                // auto run the job
                if(job.getAutoRunJob()){
                    Cause.UserIdCause cause = new Cause.UserIdCause();
                    item.scheduleBuild(5, cause);
                }
            }
            return Result.SUCCESS;
        }

        @Override
        public void post2(BuildListener listener) throws Exception {
        }

        /**
         * Execute all downstream projects and pass template parameters to them.
         * Only the initiator of the build can schedule the build of the
         * downstream project.
         */
        @Override
        public void cleanUp(BuildListener listener) throws Exception {
            JobGenerator job = getJobGenerator();
            if(job.getProcessThisJobOnly() || job.getDelete()){
                return;
            }
            for(DownstreamGenerator dg: downstreamGenerators){
                for(List<ParametersAction> lpa: dg.importParams){
                    Cause.UpstreamCause cause = new Cause.UpstreamCause(
                                                                   getBuild());
                    dg.job.scheduleBuild2(0, cause, lpa);
                    }
            }
        }

        private void resolveDownstreamJobNames(){
            JobGenerator job = getJobGenerator();
            for(DownstreamGenerator dg: downstreamGenerators){
                Visitor v = new ExpandDownstreamJobNamesVisitor(
                                                     dg, job.getDisableJobs());
                dg.node.accept(v);
            }
        }

        private void gatherDownstreamJobsFromBuildSteps(
                Document doc) throws Exception {
            List<ParametersAction> lpa = getBuild().getActions(
                                          hudson.model.ParametersAction.class);
            Element nodebuilders = (Element) doc.selectSingleNode(
                                                          "/project/builders");
            List<String> rootNames = new ArrayList<String>();
            // param build trigger with single cond.
            rootNames.add("org.jenkinsci.plugins." +
                   "conditionalbuildstep.singlestep.SingleConditionalBuilder");
            // param build trigger with multiple step cond.
            rootNames.add("org.jenkinsci.plugins." +
                                    "conditionalbuildstep.ConditionalBuilder");
            // param build trigger without cond.
            rootNames.add("hudson.plugins." +
                                        "parameterizedtrigger.TriggerBuilder");
            for(String rootName: rootNames){
                List roots = nodebuilders.elements(rootName);
                for (Iterator i = roots.iterator(); i.hasNext();) {
                    Element root = (Element) i.next();
                    this.gatherDownstreamGeneratorsFromTriggerBuilder(
                                                          root, lpa, listener);
                }
            }
        }

        private void gatherDownstreamGeneratorsFromTriggerBuilder(
                Element root,
                List<ParametersAction> params,
                BuildListener listener) throws Exception {
            JobGenerator job = getJobGenerator();
            List nodes = root.selectNodes("//configs/hudson.plugins." +
                           "parameterizedtrigger.BlockableBuildTriggerConfig");
            for (Iterator i = nodes.iterator(); i.hasNext();){
                Element node = (Element) i.next();
                InputStream is =
                  new ByteArrayInputStream(node.asXML().getBytes("UTF-8"));
                XStream2 xs = new XStream2();
                BlockableBuildTriggerConfig btc =
                                  (BlockableBuildTriggerConfig) xs.fromXML(is);
                for (AbstractProject p : btc.getProjectList(job.getParent(),
                                                                       null)) {
                    List<List<ParametersAction>> importParams =
                                   new ArrayList<List<ParametersAction>>();
                    importParams.add(new ArrayList<ParametersAction>());
                    importParams.get(0).addAll(params);
                    List<AbstractBuildParameters> lbp = btc.getConfigs();
                    for(AbstractBuildParameters bp: lbp){
                        if(bp.getClass().getSimpleName().equals(
                                    "PredefinedGeneratorParameters")){
                            importParams.get(0).add(
                                (ParametersAction)bp.getAction(
                                        GeneratorRun.this, listener));
                        }
                    }
//                    List<List<AbstractBuildParameters>> llbpf =
//                            this.getDynamicBuildParameters(
//                                        (AbstractBuild<?, ?>)getBuild(),
//                                        listener, c.getConfigFactories());
//                    int importIndex = importParams.size();
//                    for(List<AbstractBuildParameters> lbpf: llbpf){
//                        importParams.add(
//                                    new ArrayList<ParametersAction>());
//                        importParams.get(importIndex).addAll(params);
//                        for(AbstractBuildParameters bpf: lbpf){
//                            if(bpf.getClass().getSimpleName().equals(
//                                    "PredefinedGeneratorParameters")){
//                                importParams.get(importIndex).add(
//                                    (ParametersAction) bpf.getAction(
//                                          GeneratorRun.this, listener));
//                            }
//                        }
//                        importIndex += 1;
//                    }
                    if (JobGenerator.class.isInstance(p)){
                        job.copyOptions((JobGenerator) p);
                        downstreamGenerators.add(
                               new DownstreamGenerator(p, importParams, node));
                    }
                }
            }
        }

        private void gatherDownstreamJobsFromPublishers(
                Document doc) throws Exception{
            List<ParametersAction> lpa = getBuild().getActions(
                                          hudson.model.ParametersAction.class);
            Element nodepublishers = (Element) doc.selectSingleNode(
                                                        "/project/publishers");
            List<String> rootNames = new ArrayList<String>();
            // param build triggers in flexible pub.
            rootNames.add("org.jenkins__ci.plugins." +
                          "flexible__publish.FlexiblePublisher");
            // param build trigger no cond.
            rootNames.add("hudson.plugins.parameterizedtrigger.BuildTrigger");
            for(String rootName: rootNames){
                List roots = nodepublishers.elements(rootName);
                for (Iterator i = roots.iterator(); i.hasNext();) {
                    Element root = (Element) i.next();
                    this.gatherDownstreamGeneratorsFromBuildTrigger(
                                                          root, lpa, listener);
                }
            }
            // legacy build triggers
            List nodes = nodepublishers.selectNodes(
                                                  "hudson.tasks.BuildTrigger");
            for (Iterator i = nodes.iterator(); i.hasNext();) {
                Element node = (Element) i.next();
                InputStream is =
                      new ByteArrayInputStream(node.asXML().getBytes("UTF-8"));
                XStream2 xs = new XStream2();
                hudson.tasks.BuildTrigger bt =
                        (hudson.tasks.BuildTrigger) xs.fromXML(is);
                this.gatherDownstreamGeneratorsFromVanillaBuildTrigger(
                                                                bt, lpa, node);
            }
        }

        private void gatherDownstreamGeneratorsFromBuildTrigger(
                Element root,
                List<ParametersAction> params,
                BuildListener listener) throws Exception {
            JobGenerator job = getJobGenerator();
            List nodes = root.selectNodes("//configs/hudson.plugins." +
                                "parameterizedtrigger.BuildTriggerConfig");
            for (Iterator i = nodes.iterator(); i.hasNext();){
                Element node = (Element) i.next();
                InputStream is =
                  new ByteArrayInputStream(node.asXML().getBytes("UTF-8"));
                XStream2 xs = new XStream2();
                BuildTriggerConfig btc = (BuildTriggerConfig) xs.fromXML(is);
                for (AbstractProject p : btc.getProjectList(job.getParent(),
                                                                       null)) {
                    List<List<ParametersAction>> importParams =
                                   new ArrayList<List<ParametersAction>>();
                    importParams.add(new ArrayList<ParametersAction>());
                    importParams.get(0).addAll(params);
                    List<AbstractBuildParameters> lbp = btc.getConfigs();
                    for(AbstractBuildParameters bp: lbp){
                        if(bp.getClass().getSimpleName().equals(
                                        "PredefinedGeneratorParameters")){
                            importParams.get(0).add((ParametersAction)
                                bp.getAction(GeneratorRun.this, listener));
                        }
                    }
                    if (JobGenerator.class.isInstance(p)){
                        job.copyOptions((JobGenerator) p);
                        downstreamGenerators.add(
                           new DownstreamGenerator(p, importParams, node));
                    }
                }
            }
        }

        private void gatherDownstreamGeneratorsFromVanillaBuildTrigger(
                hudson.tasks.BuildTrigger bt,
                List<ParametersAction> params,
                Element node) {
            JobGenerator job = getJobGenerator();
            List<AbstractProject> apl = bt.getChildProjects(job);
            for (AbstractProject ap: apl){
                if (JobGenerator.class.isInstance(ap)){
                    job.copyOptions((JobGenerator) ap);
                    List<List<ParametersAction>> importParams =
                               new ArrayList<List<ParametersAction>>();
                    importParams.add(new ArrayList<ParametersAction>());
                    importParams.get(0).addAll(params);
                    downstreamGenerators.add(
                            new DownstreamGenerator(ap, importParams, node));
                }
            }
        }

        /**
         * @return Inner list represents a set of build parameters used together
         *         for one invocation of a project, and outer list represents
         *         multiple invocations of the same project.
         */
        private List<List<AbstractBuildParameters>> getDynamicBuildParameters(
                AbstractBuild<?, ?> build, BuildListener listener,
                List<AbstractBuildParameterFactory> configFactories)
                throws DontTriggerException, IOException, InterruptedException {
            if (configFactories == null || configFactories.isEmpty()) {
                return ImmutableList
                        .<List<AbstractBuildParameters>> of(ImmutableList
                                .<AbstractBuildParameters> of());
            } else {
                // this code is building the combinations of all
                // AbstractBuildParameters reported from all factories
                List<List<AbstractBuildParameters>> dynamicBuildParameters =
                        Lists.newArrayList();
                dynamicBuildParameters.add(
                        Collections.<AbstractBuildParameters> emptyList());
                for (AbstractBuildParameterFactory configFactory:
                    configFactories) {
                    List<List<AbstractBuildParameters>>
                    newDynParameters = Lists.newArrayList();
                    List<AbstractBuildParameters> factoryParameters =
                            configFactory.getParameters(build, listener);
                    // if factory returns 0 parameters we need to skip
                    // assigning newDynParameters to dynamicBuildParameters
                    // as we would add invalid list
                    if (factoryParameters.size() > 0) {
                        for (AbstractBuildParameters config:
                            factoryParameters) {
                            for (List<AbstractBuildParameters>
                            dynamicBuildParameter: dynamicBuildParameters) {
                                newDynParameters.add(ImmutableList
                                        .<AbstractBuildParameters> builder()
                                        .addAll(dynamicBuildParameter)
                                        .add(config).build());
                            }
                        }
                        dynamicBuildParameters = newDynParameters;
                    }
                }
                return dynamicBuildParameters;
            }
        }

        private void deleteJobs(JobGenerator job, boolean deleteChildren,
                                List<String> deletedJobs){
            int n = getLastMatchingGeneratedSuccessfulBuild(job);
            this.deleteJob(job, n, deletedJobs);
            if(!deleteChildren){
                return;
            }
            // delete children
            BuildTrigger bt = job.getPublishersList().get(BuildTrigger.class);
            if (bt != null) {
                // parameterized build trigger
                for (ListIterator<BuildTriggerConfig> btc =
                        bt.getConfigs().listIterator(); btc.hasNext();) {
                    BuildTriggerConfig c = btc.next();
                    for (AbstractProject p : c.getProjectList(job.getParent(),
                                                              null)) {
                        if(JobGenerator.class.isInstance(p)){
                            this.deleteJobs((JobGenerator) p, deleteChildren,
                                            deletedJobs);
                        }
                    }
                }
            }
            else{
                // standard Jenkins dependencies
                for(AbstractProject dp: job.getDownstreamProjects()){
                    if(JobGenerator.class.isInstance(dp)){
                        this.deleteJobs((JobGenerator) dp, deleteChildren,
                                        deletedJobs);
                    }
                }
            }
        }

        private int getLastMatchingGeneratedSuccessfulBuild(JobGenerator job) {
            final List<ParametersAction> params = getBuild().getActions(
                    hudson.model.ParametersAction.class);
            final String matchingName = getExpandedJobName(job, params);

            GeneratorRun potentialSucceededBuild = job.getLastSuccessfulBuild();

            while (potentialSucceededBuild != null) {
                final String generatedJobName = getExpandedJobName(potentialSucceededBuild.getJobGenerator(),
                        potentialSucceededBuild.getActions(hudson.model.ParametersAction.class));
                if (matchingName.equals(generatedJobName) && potentialSucceededBuild.getAction(GeneratedJobBuildAction.class) != null) {
                    return potentialSucceededBuild.getNumber();
                }
                potentialSucceededBuild = potentialSucceededBuild.getPreviousSuccessfulBuild();
            }
            throw new IllegalStateException("Cannot determine latest success generating build for job : " + matchingName);
        }

        private void deleteJob(JobGenerator job, int buildnum,
                               List<String> deletedJobs){
            GeneratedJobBuildAction a =
                job.getBuildByNumber(buildnum).getAction(
                                                GeneratedJobBuildAction.class);
            if(a == null){
                this.deleteJobFromPreviousBuild(job, buildnum, deletedJobs);
            }
            String genjobn = a.getJob();
            TopLevelItem i = Jenkins.getInstance().getItem(genjobn);
            if(i != null){
                try {
                    i.delete();
                    deletedJobs.add(genjobn);
                }
                catch (Exception e) {
                    LOGGER.severe(String.format("Error deleting job %s",
                                                genjobn));
                }
                LOGGER.info(String.format("Deleted job %s", genjobn));
            }
            else{
                this.deleteJobFromPreviousBuild(job, buildnum, deletedJobs);
            }
        }
        
        private void deleteJobFromPreviousBuild(JobGenerator job,
                                                int buildnum,
                                                List<String> deletedJobs){
            buildnum = buildnum - 1;
            LOGGER.info("Job does not exist. Trying previous build.");
            if (buildnum > 0){
                this.deleteJob(job, buildnum, deletedJobs);
            }
        }

        private void removeNodeIfNoChild(Node root, String elem) {
            List list = root.selectNodes("//" + elem);
            for (Iterator iter = list.iterator(); iter.hasNext(); ) {
                Node node = (Node) iter.next();
                if(node.selectNodes("./*").isEmpty()){
                    node.detach();
                }
            }
        }

        private boolean checkParameters(BuildListener listener) {
            JobGenerator job = getJobGenerator();
            List<ParametersAction> params = getBuild().getActions(
                                          hudson.model.ParametersAction.class);
            String expName = getExpandedJobName(job, params);
            if(job.getGeneratedJobName().isEmpty()){
                listener.error("Generated Project Name cannot be empty. " +
                               "Please review the configuration of the " +
                               "project.");
                return false;
            }
            else if(job.getName().equals(expName)){
                listener.error("Generated Project Name cannot be equal " +
                               "to the Job Generator name. " +
                               "Please review the configuration of the " +
                               "project.");
                return false;
            }
            else{
                // check if the expanded name correspond to another job
                // generator
                TopLevelItem i = Jenkins.getInstance().getItem(expName);
                if(i != null){
                    if(JobGenerator.class.isInstance(i)){
                        listener.error("Generated Project Name corresponds " +
                                       "to a the Job Generator " +
                                       i.getName() +
                                       ". Generation has been aborted to " +
                                       "prevent any loss of data.");
                return false;
                    }
                }
            }

            return true;
        }
    }

    class ExpandVarsVisitor extends VisitorSupport {
        private final List<ParametersAction> params;
        private final boolean disableJob;

        public ExpandVarsVisitor(
                List<ParametersAction> params,
                boolean disableJob){
            this.params = params;
            this.disableJob = disableJob;
        }

        @Override
        public void visit(Element node) {
            String n = node.getName();
            if(n.equals("triggerWithNoParameters")){
                // force trigger without any parameter
                node.setText("true");
            }
            else if(n.equals("disabled") &&
                    node.getParent().getName().equals("project")){
                if(this.disableJob){
                    node.setText("true");
                }
                else{
                    node.setText("false");
                }
            }
        }

        @Override
        public void visit(Text node){
            node.setText(GeneratorRun.expand(node.getText(), this.params));
        }
    }

    class ExpandDownstreamJobNamesVisitor extends VisitorSupport {
        private final DownstreamGenerator dg;
        private final boolean disableJob;

        public ExpandDownstreamJobNamesVisitor(
                DownstreamGenerator dg,
                boolean disableJob){
            this.dg = dg;
            this.disableJob = disableJob;
        }

        @Override
        public void visit(Text node){
            node.setText(this.updateProjectReference(node));
        }
 
        private String updateProjectReference(Text node){
            String result = "";
            for(String s: node.getText().split(",")){
                s = Util.fixEmptyAndTrim(s);
                if(s != null && dg.job.getName().equals(s)){
                    for(List<ParametersAction> lpa: dg.importParams){
                        if(result.length() > 0){
                            result += ",";
                        }
                        if (JobGenerator.class.isInstance(dg.job)){
                            result += GeneratorRun.getExpandedJobName(
                                            (JobGenerator)dg.job, lpa);
                        }
                        else {
                            result += dg.job.getName();
                        }
                    }
                    break;
                }
            }
            if(result.length() > 0){
                return result;
            }
            else{
                return node.getText();
            }
        }
    }

    class GatherElementsToRemoveVisitor extends VisitorSupport {
        public List<Element> toRemove = new ArrayList<Element>();

        public GatherElementsToRemoveVisitor(){}

        @Override
        public void visit(Element node) {
            String n = node.getName();
            if(n.contains("GeneratorKeyValueParameterDefinition") ||
               n.contains("GeneratorChoiceParameterDefinition") ||
               n.contains("GeneratorCurrentParameters") ||
               n.contains("PredefinedGeneratorParameters") ||
               n.contains("CounterGeneratorParameterFactory") ||
               n.contains("FileGeneratorParameterFactory")){
                this.toRemove.add(node);
            }
        }
    }

    class EvaluateBuildersSingleVisitor extends VisitorSupport {
        private final Element root;
        private final AbstractBuild<?, ?> build;
        private final BuildListener listener;
        public List<Element> toAdd;
        public List<Element> toRemove;
        public EvaluateBuildersSingleVisitor(
                Element root,
                AbstractBuild<?, ?> build,
                BuildListener listener){
            this.root = root;
            this.build = build;
            this.listener = listener;
            this.toAdd = new ArrayList<Element>();
            this.toRemove = new ArrayList<Element>();
        }

        @Override
        public void visit(Element node) {
            String n = node.getName();
            if (n.equals("condition") && node.attribute("plugin") != null &&
                GeneratorRun.isEvaluationSupported(node) &&
                GeneratorRun.allParametersAreResolved(node)){
                // convert this chunk of xml config to a file
                InputStream is;
                try {
                    is = new ByteArrayInputStream(
                                               node.asXML().getBytes("UTF-8"));
                    XStream2 xs = new XStream2();
                    RunCondition rc = (RunCondition) xs.fromXML(is);
                    if(rc.runPerform(this.build, listener)){
                        Element builder =
                            (Element)this.root.selectSingleNode("buildStep");
                        Element ne = builder.createCopy();
                        ne.setName(ne.attributeValue("class"));
                        ne.attribute("class").detach();
                        this.toAdd.add(ne);
                    }
                    this.toRemove.add(this.root);
                } catch (UnsupportedEncodingException e) {
                } catch (FileNotFoundException e) {
                } catch (IOException e) {
                } catch (Exception e) {
                }
            }
        }
    }

    class EvaluateBuildersMultiVisitor extends VisitorSupport {
        private final Element root;
        private final AbstractBuild<?, ?> build;
        private final BuildListener listener;
        public List<Element> toAdd;
        public List<Element> toRemove;
        public EvaluateBuildersMultiVisitor(
                Element root,
                AbstractBuild<?, ?> build,
                BuildListener listener){
            this.root = root;
            this.build = build;
            this.listener = listener;
            this.toAdd = new ArrayList<Element>();
            this.toRemove = new ArrayList<Element>();
        }

        @Override
        public void visit(Element node) {
            String n = node.getName();
            if (n.equals("runCondition") && node.attribute("plugin") != null &&
                GeneratorRun.isEvaluationSupported(node) &&
                GeneratorRun.allParametersAreResolved(node)){
                try {
                    InputStream is = new ByteArrayInputStream(
                                               node.asXML().getBytes("UTF-8"));
                    XStream2 xs = new XStream2();
                    RunCondition rc = (RunCondition) xs.fromXML(is);
                    if(rc.runPerform(this.build, listener)){
                        Element broot = (Element)this.root.selectSingleNode(
                                                      "conditionalbuilders");
                        if (broot != null){
                            List builders = broot.elements();
                            for (Iterator i = builders.iterator();
                                                                i.hasNext();) {
                                Element b = (Element) i.next();
                                Element ne = b.createCopy();
                                this.toAdd.add(ne);
                            }
                        }
                    }
                    this.toRemove.add(this.root);
                } catch (UnsupportedEncodingException e) {
                } catch (FileNotFoundException e) {
                } catch (IOException e) {
                } catch (Exception e) {
                }
            }
        }
    }


    class EvaluatePublishersVisitor extends VisitorSupport {
        private final Element root;
        private final AbstractBuild<?, ?> build;
        private final BuildListener listener;
        public List<Element> toAdd;
        public List<Element> toRemove;
        public EvaluatePublishersVisitor(
                Element root,
                AbstractBuild<?, ?> build,
                BuildListener listener){
            this.root = root;
            this.build = build;
            this.listener = listener;
            this.toAdd = new ArrayList<Element>();
            this.toRemove = new ArrayList<Element>();
        }

        @Override
        public void visit(Element node) {
            String n = node.getName();
            if (n.equals("condition") && node.attribute("plugin") != null &&
                GeneratorRun.isEvaluationSupported(node) &&
                GeneratorRun.allParametersAreResolved(node)){
                // convert this chunk of xml config to a file
                InputStream is;
                try {
                    is = new ByteArrayInputStream(
                                               node.asXML().getBytes("UTF-8"));
                    XStream2 xs = new XStream2();
                    RunCondition rc = (RunCondition) xs.fromXML(is);
                    if(rc.runPerform(this.build, listener)){
                        Element builder =
                            (Element)this.root.selectSingleNode("publisher");
                        Element ne = builder.createCopy();
                        ne.setName(ne.attributeValue("class"));
                        ne.attribute("class").detach();
                        this.toAdd.add(ne);
                    }
                    this.toRemove.add(this.root);
                } catch (UnsupportedEncodingException e) {
                } catch (FileNotFoundException e) {
                } catch (IOException e) {
                } catch (Exception e) {
                }
            }
        }
    }
}
