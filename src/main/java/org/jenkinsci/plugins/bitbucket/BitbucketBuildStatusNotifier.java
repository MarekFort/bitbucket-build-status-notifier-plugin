package org.jenkinsci.plugins.bitbucket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import hudson.Extension;
import hudson.Launcher;
import hudson.matrix.MatrixProject;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Project;
import hudson.model.Result;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.Revision;
import hudson.plugins.git.util.BuildData;
import hudson.scm.SCM;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.triggers.SCMTriggerItem;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.bitbucket.api.*;
import org.jenkinsci.plugins.bitbucket.model.BitbucketBuildStatus;
import org.jenkinsci.plugins.bitbucket.model.BitbucketBuildStatusResource;
import org.jenkinsci.plugins.bitbucket.model.BitbucketBuildStatusSerializer;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.scribe.model.*;

public class BitbucketBuildStatusNotifier extends Notifier {

    private static final Logger logger = Logger.getLogger(BitbucketBuildStatusNotifier.class.getName());

    private final String apiKey;
    private final String apiSecret;
    private final boolean notifyStart;
    private final boolean notifyFinish;

    @DataBoundConstructor
    public BitbucketBuildStatusNotifier(final String apiKey, final String apiSecret, final boolean notifyStart,
                                        final boolean notifyFinish) {
        super();
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.notifyStart = notifyStart;
        this.notifyFinish = notifyFinish;
    }

    public String getApiKey() {
        return this.apiKey;
    }

    public String getApiSecret() {
        return this.apiSecret;
    }

    public boolean getNotifyStart() {
        return this.notifyStart;
    }

    public boolean getNotifyFinish() {
        return this.notifyFinish;
    }

    @Override
    public boolean prebuild(AbstractBuild<?, ?> build, BuildListener listener) {

        if (!this.notifyStart) {
            return true;
        }
        logger.info("Bitbucket notify on start");

        try {
            List<BitbucketBuildStatusResource> buildStatusResourceList = this.createBuildStatusResourceListFromBuild(build);
            BitbucketBuildStatus buildStatus = this.createBitbucketBuildStatusFromBuild(build);
            for (BitbucketBuildStatusResource buildStatusResource : buildStatusResourceList) {
                this.notifyBuildStatus(buildStatusResource, buildStatus);
            }
            listener.getLogger().println("Sending build status " + buildStatus.getState() + " to BitBucket is done!");
        } catch (Exception e) {
            logger.log(Level.INFO, "Bitbucket notify on start failed: " + e.getMessage(), e);
            listener.getLogger().println("Bitbucket notify on start failed: " + e.getMessage());
            e.printStackTrace(listener.getLogger());
        }

        logger.info("Bitbucket notify on start succeeded");

        return true;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {

        if (!this.notifyFinish) {
            return true;
        }
        logger.info("Bitbucket notify on finish");

        try {
            BitbucketBuildStatus buildStatus = this.createBitbucketBuildStatusFromBuild(build);
            for (BitbucketBuildStatusResource buildStatusResource : createBuildStatusResourceListFromBuild(build)) {            
                this.notifyBuildStatus(buildStatusResource, buildStatus);
            }
            listener.getLogger().println("Sending build status " + buildStatus.getState() + " to BitBucket is done!");
        } catch (Exception e) {
            logger.log(Level.INFO, "Bitbucket notify on finish failed: " + e.getMessage(), e);
            listener.getLogger().println("Bitbucket notify on finish failed: " + e.getMessage());
            e.printStackTrace(listener.getLogger());
        }

        logger.info("Bitbucket notify on finish succeeded");

        return true;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return Jenkins.getInstance().getDescriptorByType(DescriptorImpl.class);
    }

    @Override
    public boolean needsToRunAfterFinalized() {
        //This is here to ensure that the reported build status is actually correct. If we were to return false here,
        //other build plugins could still modify the build result, making the sent out HipChat notification incorrect.
        return true;
    }

    private String guessBitbucketBuildState(final Result result) {

        String state = null;

        // possible statuses SUCCESS, UNSTABLE, FAILURE, NOT_BUILT, ABORTED
        if (result == null) {
            state = BitbucketBuildStatus.INPROGRESS;
        } else if (Result.SUCCESS == result) {
            state = BitbucketBuildStatus.SUCCESSFUL;
        } else if (Result.FAILURE == result) {
            state = BitbucketBuildStatus.FAILED;
        }

        return state;
    }
    
    private List<BitbucketBuildStatusResource> createBuildStatusResourceListFromBuild(final AbstractBuild build) throws Exception {   
        SCM scm = build.getProject().getScm();
        if (scm == null) {
            throw new Exception("Bitbucket build notifier only works with SCM");
        }        
        
        Map<String, String> buildRevisions = getBuildGitRevisions(build);        
        List<BitbucketBuildStatusResource> resourceList = new ArrayList();
        for (GitSCM gitSCM : getProjectGitSCMs(build.getProject())) {
            resourceList.add(createBuildStatusResourceFromBuild(build, gitSCM, buildRevisions));
        }
        return resourceList;
    }
    
    // Parse revisions from build    
    public static Map<String, String> getBuildGitRevisions(AbstractBuild build) {
        Map<String, String> buildRevisions = new HashMap();

        List<BuildData> buildDataList = build.getActions(BuildData.class);
        for (BuildData buildData : buildDataList) {
            String url = null;
            for (String remoteUrl : buildData.getRemoteUrls()) {
                url = remoteUrl;
                break;
            }

            if (url != null) { // Git repository has valid url
                Revision lastBuiltRevision = buildData.getLastBuiltRevision();
                if (lastBuiltRevision != null) {
                    buildRevisions.put(url, lastBuiltRevision.getSha1String()); // get Git revision from last entry
                } else {
                    for (hudson.plugins.git.util.Build b : buildData.getBuildsByBranchName().values()) {
                        buildRevisions.put(url, b.getSHA1().getName()); // get Git revision from the first entry
                        break;
                    }
                }
            }
        }

        return buildRevisions;
    }

    // Filter Git SCM from project config
    public static List<GitSCM> getProjectGitSCMs(AbstractProject project) {
        List<GitSCM> gitSCMs = new ArrayList();
        for (Object scm : getProjectSCMs(project)) {
            if (scm instanceof GitSCM) { // filter Git only
                gitSCMs.add((GitSCM) scm);
            }
        }
        
        return gitSCMs;
    }

    private static Collection getProjectSCMs(AbstractProject project) {
        if (project instanceof MatrixProject) {
            MatrixProject mp = (MatrixProject) project;
            return SCMTriggerItem.SCMTriggerItems.resolveMultiScmIfConfigured(mp.getScm());
        } else {
            Project p = (Project) project;
            return  p.getSCMs();
        }
    }

    private BitbucketBuildStatusResource createBuildStatusResourceFromBuild(final AbstractBuild build, final GitSCM scm, final  Map<String, String> buildRevisions) throws Exception {
        if (!(scm instanceof GitSCM)) {
            throw new Exception("Bitbucket build notifier requires a git repo as SCM");
        }

        GitSCM gitSCM = (GitSCM) scm;
        List<RemoteConfig> repoList = gitSCM.getRepositories();
        if (repoList.size() != 1) {
            throw new Exception("None or multiple repos");
        }

        URIish urIish = repoList.get(0).getURIs().get(0);
        if (!urIish.getHost().equals("bitbucket.org")) {
            throw new Exception("Bitbucket build notifier support only repositories hosted in bitbucket.org");
        }

        // extract bitbucket user name and repository name from repo URI
        String repoUrl = urIish.getPath();

        String repoName = repoUrl.substring(repoUrl.lastIndexOf("/") + 1, repoUrl.indexOf(".git"));
        if (repoName.isEmpty()) {
            throw new Exception("Bitbucket build notifier could not extract the repository name from the repository URL");
        }

        String userName = repoUrl.substring(0, repoUrl.indexOf("/" + repoName));
        if (userName.contains("/")) {
            userName = userName.substring(userName.indexOf("/") + 1, userName.length());
        }
        if (userName.isEmpty()) {
            throw new Exception("Bitbucket build notifier could not extract the user name from the repository URL");
        }
                
        String commitId = buildRevisions.get(urIish.toString());
        if (commitId == null) {
            throw new Exception("Commit ID could not be found!");
        }
        
        return new BitbucketBuildStatusResource(userName, repoName, commitId);
    }

    private void notifyBuildStatus(final BitbucketBuildStatusResource buildStatusResource, final BitbucketBuildStatus buildStatus) throws Exception {

        OAuthConfig config = new OAuthConfig(this.apiKey, this.apiSecret);
        BitbucketApiService apiService = (BitbucketApiService) new BitbucketApi().createService(config);

        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(BitbucketBuildStatus.class, new BitbucketBuildStatusSerializer());
        gsonBuilder.setPrettyPrinting();
        Gson gson = gsonBuilder.create();

        OAuthRequest request = new OAuthRequest(Verb.POST, buildStatusResource.generateUrl(Verb.POST));
        request.addHeader("Content-type", "application/json");
        request.addPayload(gson.toJson(buildStatus));

        Verifier verifier = null;
        Token token = apiService.getAccessToken(OAuthConstants.EMPTY_TOKEN, verifier);
        apiService.signRequest(token, request);

        logger.info("This response was received:");

        Response response = request.send();
    }

    private BitbucketBuildStatus createBitbucketBuildStatusFromBuild(AbstractBuild build) {

        String buildState = this.guessBitbucketBuildState(build.getResult());

        String buildKey = build.getProject().getFullDisplayName() + "#" + build.getNumber();
        String buildUrl = build.getProject().getAbsoluteUrl() + build.getNumber() + '/';
        String buildName = build.getProject().getFullDisplayName() + " #" + build.getNumber();

        return new BitbucketBuildStatus(buildState, buildKey, buildUrl, buildName);
    }

    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        @Override
        public String getDisplayName() {
            return "Bitbucket notify build status";
        }

        public DescriptorImpl() {
            load();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }


        public FormValidation doCheckApiKey(@QueryParameter final String apiKey, @QueryParameter final String apiSecret) throws FormException {

            if (apiKey.isEmpty() || apiSecret.isEmpty()) {
                return FormValidation.error("Please enter Bitbucket OAuth credentials");
            }

            try {
                OAuthConfig config = new OAuthConfig(apiKey, apiSecret);
                BitbucketApiService apiService = (BitbucketApiService) new BitbucketApi().createService(config);
                Verifier verifier = null;
                Token token = apiService.getAccessToken(OAuthConstants.EMPTY_TOKEN, verifier);

                if (token.isEmpty()) {
                    return FormValidation.error("Invalid Bitbucket OAuth credentials");
                }

            } catch (Exception e) {
                return FormValidation.error(e.getClass() + e.getMessage());
            }

            return FormValidation.ok();
        }
    }
}
