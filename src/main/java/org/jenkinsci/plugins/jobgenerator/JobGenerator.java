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

import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.*;
import hudson.model.Descriptor.FormException;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue.FlyweightTask;
import hudson.model.labels.LabelAtom;
import hudson.scm.PollingResult;
import hudson.triggers.SCMTrigger.SCMTriggerCause;
import hudson.triggers.TimerTrigger.TimerTriggerCause;
import hudson.util.AlternativeUiTextProvider;
import hudson.util.FormValidation;

import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.servlet.ServletException;

import jenkins.util.TimeDuration;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import com.google.common.collect.Lists;

import org.jenkinsci.plugins.jobgenerator.parameters.*;

/**
 * Defines project which build action is to generates a new job configuration.
 *
 * @author <a href="mailto:sylvain.benner@gmail.com">Sylvain Benner</a>
 */
public class JobGenerator extends Project<JobGenerator, GeneratorRun>
                          implements TopLevelItem, FlyweightTask, SCMedItem {

    private transient boolean delete = false;
    private transient boolean processThisJobOnly = false;
    private transient boolean disableJobs = false;
    private transient boolean initiator = false;
    private transient String customWorkspace = null;
    private String generatedJobName = "";
    private String generatedDisplayJobName = "";
    private boolean autoRunJob = false;

    @DataBoundConstructor
    public JobGenerator(ItemGroup parent, String name) {
        super(parent, name);
    }

    @Override
    public void doBuild(StaplerRequest req,
                        StaplerResponse rsp,
                        TimeDuration delay) throws IOException ,
                                                   ServletException {
        this.initiator = true;
        super.doBuild(req, rsp, delay);
    }

    @Override
    public boolean isParameterized() {
        // not working for now since doBuild method of jenkins core does not
        // use this method.
        return true;
    }

    /**
     * Returns plugin own parameter definition property which wraps the real
     * one.
     */
    @Override
    public <T extends JobProperty> T getProperty(Class<T> clazz) {
        T res = super.getProperty(clazz);
        if(ParametersDefinitionProperty.class == clazz){
            ParametersDefinitionProperty topmost =
                    (ParametersDefinitionProperty)
                                  this.getTopMostParameterDefinitionProperty();
            if(res != null){
                // wrap parameter definitions and merge with top most project
                // parameters
                GeneratorParametersDefinitionProperty newres =
                    new GeneratorParametersDefinitionProperty(
                                           (ParametersDefinitionProperty) res,
                                           this);
                if(topmost != null){
                    List<ParameterDefinition> lpd =
                                             topmost.getParameterDefinitions();
                    for(ParameterDefinition pd: Lists.reverse(lpd)) {
                        newres.getParameterDefinitions().add(0, pd);
                    }
                    newres.addGlobalParameters(lpd);
                    lpd = ((ParametersDefinitionProperty)res).
                                                     getParameterDefinitions();
                    newres.addLocalParameters(lpd);
                }
                else {
                    List<ParameterDefinition> lpd =
                        ((ParametersDefinitionProperty)res).
                                                     getParameterDefinitions();
                    newres.addGlobalParameters(lpd);
                }
                res = (T) newres;
            }
            else if(topmost != null){
                List<ParameterDefinition> lpd =
                                             topmost.getParameterDefinitions();
                GeneratorParametersDefinitionProperty newres =
                    new GeneratorParametersDefinitionProperty(topmost, this);
                newres.addGlobalParameters(lpd);
                newres.setOwner2(this);
                res = (T) newres;
            }
        }
        return res;
    }

    @Override
    public Label getAssignedLabel() {
        return new LabelAtom("master");
    }

    @Override
    public boolean checkout(AbstractBuild build, Launcher launcher,
            BuildListener listener, File changelogFile) throws IOException,
            InterruptedException {
        return true;
    }

    @Override
    public boolean schedulePolling() {
        return false;
    }

    @Override
    public PollingResult poll(TaskListener listener) {
        return PollingResult.NO_CHANGES;
    }

    @Override
    public boolean scheduleBuild(int quietPeriod, Cause c, Action... actions) {
        if(!SCMTriggerCause.class.isInstance(c) &&
           !TimerTriggerCause.class.isInstance(c)){
            return super.scheduleBuild(quietPeriod, c, actions);
        }
        return false;
    }

    @Override
    protected void submit(StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException, FormException {
        super.submit(req, rsp);
        JSONObject json = req.getSubmittedForm();
        
        String k = "plugin-jobgenerator-GeneratedJobConfig";
        JSONObject o;
        
        if(json.has(k)){
            o = json.getJSONObject(k);
            if(o != null) {
                k = "generatedJobName";
                if(o.has(k)){ this.generatedJobName = o.getString(k); }
                k = "generatedDisplayJobName";
                if(o.has(k)){ this.generatedDisplayJobName = o.getString(k); }
                k = "autoRunJob";
                if(o.has(k)){ this.autoRunJob = o.getBoolean(k);}
            }
        }
    }

    @Extension
    public static final JobGeneratorDescriptor DESCRIPTOR =
                                                  new JobGeneratorDescriptor();

    public JobGeneratorDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public String getPronoun() {
        return AlternativeUiTextProvider.get(PRONOUN, this,
                                             Messages.JobGenerator_Messages());
    }

    @Override
    protected Class<GeneratorRun> getBuildClass() {
        return GeneratorRun.class;
    }

    @SuppressWarnings("rawtypes")
    public JobProperty getTopMostParameterDefinitionProperty(){
        AbstractProject topmost = this;
        List<AbstractProject> lup = topmost.getUpstreamProjects();
        while(lup.isEmpty() == false){
            topmost = lup.get(0);
            lup = topmost.getUpstreamProjects();
        }
        if(topmost != this){
            return topmost.getProperty(ParametersDefinitionProperty.class);
        }
        return null;
    }

    public boolean isInitiator(){
        return this.initiator;
    }
    public void resetInitiator(){
        this.initiator = false;
    }

    public String getGeneratedJobName(){
        return this.generatedJobName;
    }
    public void setGeneratedJobName(String name){
        this.generatedJobName = name;
    }

    public String getGeneratedDisplayJobName(){
        return this.generatedDisplayJobName;
    }
    public void setGeneratedDisplayJobName(String name){
        this.generatedDisplayJobName = name;
    }

    public boolean getAutoRunJob(){
        return this.autoRunJob;
    }
    public void setAutoRunJob(boolean value){
        this.autoRunJob = value;
    }

    public String getCustomWorkspace() {
        return this.customWorkspace;
    }

    public void setCustomWorkspace(String customWorkspace) {
        this.customWorkspace= Util.fixEmptyAndTrim(customWorkspace);
    }

    public boolean getProcessThisJobOnly(){
        return this.processThisJobOnly;
    }
    public void setProcessThisJobOnly(boolean check){
        this.processThisJobOnly = check;
    }
    public boolean getDisableJobs(){
        return this.disableJobs;
    }
    public void setDisableJobs(boolean check){
        this.disableJobs = check;
    }
    public boolean getDelete(){
        return this.delete;
    }
    public void setDelete(boolean check){
        this.delete = check;
    }

    public void copyOptions(JobGenerator p){
        p.setDelete(this.getDelete());
        p.setProcessThisJobOnly(this.getProcessThisJobOnly());
        p.setDisableJobs(this.getDisableJobs());
    }

    public static class JobGeneratorDescriptor
                                           extends AbstractProjectDescriptor {
        @Override
        public String getDisplayName() {
            return Messages.JobGenerator_Messages();
        }

        @Override
        public TopLevelItem newInstance(ItemGroup parent, String name) {
            return new JobGenerator(parent, name);
        }

        public FormValidation doCheckGeneratedJobName(
                @QueryParameter String name,
                @QueryParameter String value) {
            if (value.equals(name)) {
                return FormValidation.error("Generated job name must be " +
                                            "different than this job " +
                                            "generator name.");
            }
            return FormValidation.validateRequired(value);
        }
 
        public FormValidation doCheckCustomWorkspace(
                @QueryParameter(value="customWorkspace") String customWorkspace){
            if(Util.fixEmptyAndTrim(customWorkspace)==null)
                return FormValidation.error(
                              Messages.JobGenerator_CustomWorkspaceEmpty());
            else
                return FormValidation.ok();
        }

        public String getDefaultEntriesPage(){
            return getViewPage(FreeStyleProject.class,
                               "configure-entries.jelly");
        }
    }

}
