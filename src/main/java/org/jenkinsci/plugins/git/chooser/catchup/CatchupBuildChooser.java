package org.jenkinsci.plugins.git.chooser.catchup;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.gitclient.RepositoryCallback;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.kohsuke.stapler.DataBoundConstructor;

import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import hudson.EnvVars;
import hudson.plugins.git.Branch;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import static java.util.Collections.emptyList;
import java.util.HashSet;
import org.eclipse.jgit.transport.RemoteConfig;

import hudson.plugins.git.*;
import hudson.plugins.git.util.BuildData;
import hudson.plugins.git.util.BuildChooser;
import hudson.plugins.git.util.BuildChooserContext;
import hudson.plugins.git.util.BuildChooserDescriptor;

public class CatchupBuildChooser extends BuildChooser {

    /* Ignore symbolic default branch ref. */
    private static final BranchSpec HEAD = new BranchSpec("*/HEAD");

    @DataBoundConstructor
    public CatchupBuildChooser() {
    }
    
    /**
     * Determines which Revisions to build.
     *
     * If only one branch is chosen and only one repository is listed, then
     * just attempt to find the latest revision number for the chosen branch.
     *
     *
     * @throws IOException
     * @throws GitException
     */
    @Override
    public Collection<Revision> getCandidateRevisions(boolean isPollCall, String branchSpec,
                                                      GitClient git, TaskListener listener, BuildData data, BuildChooserContext context)
            throws GitException, IOException, InterruptedException {

        verbose(listener,"getCandidateRevisions({0},{1},,,{2}) considering branches to build",isPollCall,branchSpec,data);

        // check if we're trying to build a specific commit
        // this only makes sense for a build, there is no
        // reason to poll for a commit
        if (!isPollCall && branchSpec.matches("[0-9a-f]{6,40}")) {
            try {
                ObjectId sha1 = git.revParse(branchSpec);
                Revision revision = new Revision(sha1);
                revision.getBranches().add(new Branch("detached", sha1));
                verbose(listener,"Will build the detached SHA1 {0}",sha1);
                return Collections.singletonList(revision);
            } catch (GitException e) {
                // revision does not exist, may still be a branch
                // for example a branch called "badface" would show up here
                verbose(listener, "Not a valid SHA1 {0}", branchSpec);
            }
        }

        Collection<Revision> revisions = new ArrayList<Revision>();

        // if it doesn't contain '/' then it could be an unqualified branch
        if (!branchSpec.contains("/")) {

            // <tt>BRANCH</tt> is recognized as a shorthand of <tt>*/BRANCH</tt>
            // so check all remotes to fully qualify this branch spec
            for (RemoteConfig config : gitSCM.getRepositories()) {
                String repository = config.getName();
                String fqbn = repository + "/" + branchSpec;
                verbose(listener, "Qualifying {0} as a branch in repository {1} -> {2}", branchSpec, repository, fqbn);
                revisions.addAll(getHeadRevisionArray(isPollCall, fqbn, git, listener, data));
            }
        } else {
            // either the branch is qualified (first part should match a valid remote)
            // or it is still unqualified, but the branch name contains a '/'
            List<String> possibleQualifiedBranches = new ArrayList<String>();
            for (RemoteConfig config : gitSCM.getRepositories()) {
                String repository = config.getName();
                String fqbn;
                if (branchSpec.startsWith(repository + "/")) {
                    fqbn = "refs/remotes/" + branchSpec;
                } else if(branchSpec.startsWith("remotes/" + repository + "/")) {
                    fqbn = "refs/" + branchSpec;
                } else if(branchSpec.startsWith("refs/heads/")) {
                    fqbn = "refs/remotes/" + repository + "/" + branchSpec.substring("refs/heads/".length());
                } else {
                    //Try branchSpec as it is - e.g. "refs/tags/mytag"
                    fqbn = branchSpec;
                }
                verbose(listener, "Qualifying {0} as a branch in repository {1} -> {2}", branchSpec, repository, fqbn);
                possibleQualifiedBranches.add(fqbn);

                //Check if exact branch name <branchSpec> existss
                fqbn = "refs/remotes/" + repository + "/" + branchSpec;
                verbose(listener, "Qualifying {0} as a branch in repository {1} -> {2}", branchSpec, repository, fqbn);
                possibleQualifiedBranches.add(fqbn);
            }
            for (String fqbn : possibleQualifiedBranches) {
              revisions.addAll(getHeadRevisionArray(isPollCall, fqbn, git, listener, data));
            }
        }

        if (revisions.isEmpty()) {
            // the 'branch' could actually be a non branch reference (for example a tag or a gerrit change)

            revisions = getHeadRevisionArray(isPollCall, branchSpec, git, listener, data);
            if (!revisions.isEmpty()) {
                verbose(listener, "{0} seems to be a non-branch reference (tag?)");
            }
        }
        
        return revisions;
    }
           
    private Collection<Revision> getHeadRevisionArray(boolean isPollCall, String singleBranch, GitClient git, TaskListener listener, BuildData data) throws InterruptedException {
        try {
            verbose(listener, "Last known commit: {0}", data.lastBuild.revision);
            verbose(listener, "Trying git rev-list {0}..{1}", data.lastBuild.revision.getSha1String(), singleBranch);
            List<ObjectId> revlist = git.revList(data.lastBuild.revision.getSha1String() + ".." + singleBranch);
            Collections.reverse(revlist);
            if(revlist.size() == 0)
                revlist = Arrays.asList(git.revParse(singleBranch));
            ArrayList<Revision> commitslist = new ArrayList();
            for ( ObjectId sha1 : revlist)
            {
                // if polling for changes don't select something that has
                // already been built as a build candidate
                if (isPollCall && data.hasBeenBuilt(sha1)) {
                    verbose(listener, "{0} has already been built", sha1);
                    break;
                }
            
                verbose(listener, "Found a new commit {0} to be built on {1}", sha1, singleBranch);
                Revision revision = new Revision(sha1);
                revision.getBranches().add(new Branch(singleBranch, sha1));
                commitslist.add(revision);
            }
            return commitslist;
        } catch (GitException e) {
            // branch does not exist, there is nothing to build
            verbose(listener, "Failed to rev-list: {0}..{1}", data.lastBuild.revision.getSha1String(), singleBranch);
            return emptyList();
        }
    }

    
    /**
     * Write the message to the listener only when the verbose mode is on.
     */
    private void verbose(TaskListener listener, String format, Object... args) {
        if (GitSCM.VERBOSE)
            listener.getLogger().println(MessageFormat.format(format,args));
    }
    
    @Extension
    public static final class DescriptorImpl extends BuildChooserDescriptor {
        @Override
        public String getDisplayName() {
            return "Catchup";
        }
    }
    
}
