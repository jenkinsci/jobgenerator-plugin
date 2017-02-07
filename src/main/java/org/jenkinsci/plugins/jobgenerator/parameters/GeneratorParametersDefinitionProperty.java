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

package org.jenkinsci.plugins.jobgenerator.parameters;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.AbstractList;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import jenkins.model.Jenkins;
import jenkins.util.TimeDuration;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;
import org.kohsuke.stapler.export.Flavor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import hudson.model.*;

import org.jenkinsci.plugins.jobgenerator.JobGenerator;

/**
 * Wrap Jenkins ParametersDefinitionProperty to be able to display its own
 * view.
 *
 * @author <a href="mailto:sylvain.benner@gmail.com">Sylvain Benner</a>
 */
@ExportedBean(defaultVisibility=2)
public class GeneratorParametersDefinitionProperty
        extends ParametersDefinitionProperty
        implements Action {

    private static final Logger LOGGER = Logger.getLogger(
                         GeneratorParametersDefinitionProperty.class.getName());

    private transient List<ParameterDefinition> generatorParameterDefinitions= new ArrayList<ParameterDefinition>();
    private transient List<ParameterDefinition> globalParameterDefinitions = new ArrayList<ParameterDefinition>();
    private transient List<ParameterDefinition> localParameterDefinitions = new ArrayList<ParameterDefinition>();

    public GeneratorParametersDefinitionProperty(
            ParametersDefinitionProperty property,
            JobGenerator project) {
        this.owner = project;
        List<ParameterDefinition> lpd = property.getParameterDefinitions();
        for(ParameterDefinition pd: lpd){
            if (GeneratorKeyValueParameterDefinition.class.isInstance(pd) ||
                GeneratorChoiceParameterDefinition.class.isInstance(pd)){
                this.generatorParameterDefinitions.add(pd);
            }
        }
    }

    // required since setOwner is protected.
    public void setOwner2(JobGenerator owner){
        this.owner = owner;
    }

    public void addGlobalParameters(List<ParameterDefinition> params){
        this.addParameters(params, this.globalParameterDefinitions);
    }
    public List<ParameterDefinition> getGlobalParameters(){
        return this.globalParameterDefinitions;
    }

    public void addLocalParameters(List<ParameterDefinition> params){
        this.addParameters(params, this.localParameterDefinitions);
    }
    public List<ParameterDefinition> getLocalParameters(){
        return this.localParameterDefinitions;
    }

    private void addParameters(List<ParameterDefinition> in,
                              List<ParameterDefinition> out){
        out.clear();
        for(ParameterDefinition pd: in){
            if(GeneratorKeyValueParameterDefinition.class.isInstance(pd) ||
               GeneratorChoiceParameterDefinition.class.isInstance(pd)) {
                out.add(pd);
            }
        }
    }

    @Override
    public void _doBuild(StaplerRequest req,
                         StaplerResponse rsp,
                         @QueryParameter TimeDuration delay)
                         throws IOException, ServletException {
        if(req.getMethod().equals("POST")) {
            JSONObject json = req.getSubmittedForm();
//            System.out.println(json);
            JSONArray a = JSONArray.fromObject(json.get("parameter"));
            for (Object o : a) {
                JSONObject jo = (JSONObject) o;
                String name = jo.getString("name");
                if(this.getParameterDefinition(name) == null){
                    String value = jo.getString("value");
                    GeneratorKeyValueParameterDefinition pdef =
                        new GeneratorKeyValueParameterDefinition(name,
                                                                 value, "");
                    this.generatorParameterDefinitions.add(pdef);
                }
            }
            JobGenerator p = (JobGenerator)this.getOwner();
            p.setDelete(false);
            JSONObject o = json.getJSONObject("delete");
            if(!o.isNullObject()){
                p.setDelete(o.getBoolean("confirm"));
            }
            o = json.getJSONObject("disablejobs");
            p.setDisableJobs(!o.isNullObject());
            o = json.getJSONObject("processthisjob");
            p.setProcessThisJobOnly(!o.isNullObject());
        }
        super._doBuild(req, rsp, new TimeDuration(0));
    }

    private boolean requestWantsJson(StaplerRequest req) {
        String a = req.getHeader("Accept");
        if (a==null)    return false;
        return !a.contains("text/html") && a.contains("application/json");
    }

    @Override
    public void buildWithParameters(
            StaplerRequest req,
            StaplerResponse rsp,
            TimeDuration delay) throws IOException, ServletException {
        List<ParameterValue> values = new ArrayList<ParameterValue>();
        for (ParameterDefinition d : this.generatorParameterDefinitions) {
            GeneratorKeyValueParameterValue value =
                          (GeneratorKeyValueParameterValue) d.createValue(req);
            if (value != null) {
                values.add(value);
            }
        }
        if (delay == null)
            delay = new TimeDuration(this.getOwner().getQuietPeriod());

        String causeText = req.getParameter("cause");
        Cause cause = new Cause.RemoteCause(req.getRemoteAddr(), causeText);

        Jenkins.getInstance().getQueue().schedule(
                this.getOwner(), delay.getTime(),
                new ParametersAction(values), new CauseAction(cause));

        if (requestWantsJson(req)) {
            rsp.setContentType("application/json");
            rsp.serveExposedBean(req, owner, Flavor.JSON);
        } else {
            // send the user back to the job top page.
            rsp.sendRedirect(".");
        }
    }

    @Exported
    @Override
    public List<ParameterDefinition> getParameterDefinitions() {
        return this.generatorParameterDefinitions;
    }

    @Override
    public List<String> getParameterDefinitionNames() {
        return new AbstractList<String>() {
            public String get(int index) {
                return generatorParameterDefinitions.get(index).getName();
            }

            public int size() {
                return (generatorParameterDefinitions.size());
            }
        };
    }

    @Override
    public ParameterDefinition getParameterDefinition(String name) {
        for (ParameterDefinition pd : this.generatorParameterDefinitions)
            if (pd.getName().equals(name))
                return pd;
        return null;
    }
}
