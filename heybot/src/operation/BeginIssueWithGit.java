package operation;

import com.taskadapter.redmineapi.RedmineManager;
import com.taskadapter.redmineapi.RedmineManagerFactory;
import com.taskadapter.redmineapi.bean.Issue;
import java.io.File;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import utilities.Properties;

public class BeginIssueWithGit extends Operation {

    // mandatory
    private final static String PARAMETER_REDMINE_URL = "REDMINE_URL";
    private final static String PARAMETER_REDMINE_TOKEN = "REDMINE_TOKEN";

    private final static String PARAMETER_ISSUE = "ISSUE";
    private final static String PARAMETER_GIT_REPOSITORY = "GIT_REPOSITORY";
    private final static String PARAMETER_GIT_PROTOCOL="GIT_PROTOCOL";
    
    // conditional optional
    private final static String PARAMETER_GIT_USERNAME="GIT_USERNAME";
    private final static String PARAMETER_GIT_PASSWORD="GIT_PASSWORD";
    private final static String PARAMETER_SSH_PRIVATE_KEY="SSH_PRIVATE_KEY";
    
    // optional
    private final static String PARAMETER_BEGAN_STATUS = "BEGAN_STATUS";
    private final static String PARAMETER_ASSIGNEE_ID = "ASSIGNEE_ID";

    private final static String PARAMETER_WORKSPACE_PATH = "WORKSPACE_PATH";
    private final static String PARAMETER_IDE_PATH = "IDE_PATH";

    private final static String PARAMETER_REMOTE_HOST = "REMOTE_HOST";
    private final static String PARAMETER_REMOTE_USER = "REMOTE_USER";
    private final static String PARAMETER_REMOTE_PASS = "REMOTE_PASS";
    private final static String PARAMETER_REMOTE_EXEC = "REMOTE_EXEC";
    private final static String PARAMETER_REMOTE_PORT = "REMOTE_PORT";

    private RedmineManager redmineManager;
    
    private final static HashSet<String> supportedProtocols = new HashSet<>(Arrays.asList("http", "https","ssh"));
    private CredentialsProvider credentialsProvider;
    private String repository;

    public BeginIssueWithGit() {
        super(new String[]{
            PARAMETER_ISSUE, PARAMETER_REDMINE_TOKEN, PARAMETER_REDMINE_URL, PARAMETER_GIT_REPOSITORY, PARAMETER_GIT_PROTOCOL
        });
    }

    @Override
    protected void execute(Properties prop) throws Exception {
        if (areMandatoryParametersNotEmpty(prop)) {
            String redmineAccessToken = getParameterString(prop, PARAMETER_REDMINE_TOKEN, false);
            String redmineUrl = getParameterString(prop, PARAMETER_REDMINE_URL, false);
            redmineManager = RedmineManagerFactory.createWithApiKey(redmineUrl, redmineAccessToken);

            Issue issue;
            if ((issue = getIssue(redmineManager, getParameterInt(prop, PARAMETER_ISSUE, 0))) == null) {
                return;
            }
            System.out.println("#" + issue.getId() + " - " + issue.getSubject());
            if (!isIssueAssigneeOk(issue, prop) || !isGitCredentialsOk(prop)) {
                return;
            }
            createBranch(issue, prop);
        }
    }
    
    private boolean isIssueAssigneeOk(Issue issue, Properties prop) {
        int assigneeId = getParameterInt(prop, PARAMETER_ASSIGNEE_ID, 0);
        System.out.println("[i] #" + issue.getId() + " is assigned to " + issue.getAssigneeName() + ".");
        if (assigneeId > 0) {
            System.out.println("[*] Checking issue assignee ...");
            if (issue.getAssigneeId() != assigneeId) {
                System.out.println("\t[e] Issue assignee failed!");
                System.out.println("\t[i] (suggested option!) Change assignee to yourself if you're sure to begin this issue.");
                System.out.println("\t[i] (not suggested but) You can comment " + PARAMETER_ASSIGNEE_ID + " from config file to disable assignee check.");
                return false;
            } else {
                System.out.println("\t[✓] Issue assignee is ok.");
            }
        } else {
            System.out.println("[i] Issue assignee check is disabled.");
        }
        return true;
    }

    private boolean isGitCredentialsOk(Properties prop) {
        System.out.println("[*] Checking git credentials ...");
        String protocol = getParameterString(prop, PARAMETER_GIT_PROTOCOL, true);
        if (!supportedProtocols.contains(protocol))
        {
            System.out.println("[e] Unrecognized git protocol: " + protocol);
            System.out.println("[i] Supported protocols: " + String.join(",", supportedProtocols));
            return false;
        }
        repository = getRepositoryWithProtocol(trimRight(getParameterString(prop, PARAMETER_GIT_REPOSITORY, false), "/"), protocol);
        credentialsProvider = getCredentialsProvider(prop, protocol);
        try {
            LsRemoteCommand lsRemote = new LsRemoteCommand(null);
            lsRemote.setCredentialsProvider(credentialsProvider);
            lsRemote.setRemote(repository);
            lsRemote.setHeads(true);
            Collection<Ref> refs = lsRemote.call();
            System.out.println("\t[i] " + refs.size() + " remote (head) refs found.");
            System.out.println("\t[✓] Git credentials are ok.");
            // https://github.com/centic9/jgit-cookbook/blob/master/src/main/java/org/dstadler/jgit/porcelain/ListRemotes.java
            return true;
        } catch (Exception ex) {
            System.out.println("\t[e] Credentials failed! " + ex.getClass().getCanonicalName() + " " + ex.getMessage());
        }
        return false;
    }

    private String getRepositoryWithProtocol(String repository, String protocol) {
        int indexOfColonWithDoubleSlash;
        if ((indexOfColonWithDoubleSlash = repository.indexOf("://")) > -1) {
            String protocolInURL = repository.substring(0, indexOfColonWithDoubleSlash);
            if (!supportedProtocols.contains(protocolInURL)) {
                repository = protocol + repository.substring(indexOfColonWithDoubleSlash + 3);
            }
        } else {
            repository = protocol + "://" + repository;
        }
        return repository;
    }

    private CredentialsProvider getCredentialsProvider(Properties prop, String protocol) {
        if (protocol.equals("https") || protocol.equals("http")) {
            String username = getParameterString(prop, PARAMETER_GIT_USERNAME, false);
            String password = getParameterString(prop, PARAMETER_GIT_PASSWORD, false);
            if (!StringUtils.isBlank(username) && !StringUtils.isBlank(password)) {
                return new UsernamePasswordCredentialsProvider(username, password);
            }
        } else if (protocol.equals("ssh")) {

        }
        return null;
    }
    
    private boolean createBranch(Issue issue, Properties prop) {
        System.out.println("[*] Creating branch ...");
        String cacheDir = getWorkingDirectory() + "/" + "cache";
        createFolder(cacheDir);
        File cachePath = new File(cacheDir + "/" + 
                new File(trimRight(getParameterString(prop, PARAMETER_GIT_REPOSITORY, false), "/")).getName());
        boolean isCacheReady;
        if (!cachePath.exists()) {
            isCacheReady = cloneRepository(cachePath);
        } else {
            // pull
        }

        return true;
    }
    
    private boolean cloneRepository(File cachePath) {
        System.out.println("\t[*] Locally cached repository not found. Cloning once ...");
        try (Git result = Git.cloneRepository()
                .setURI(repository)
                .setCredentialsProvider(credentialsProvider)
                .setProgressMonitor(new TextProgressMonitor(new PrintWriter(System.out)))
                .setDirectory(cachePath)
                .call()) {
            System.out.println("\t[✓] Cloned repository: " + result.getRepository().getDirectory());
            return true;
        } catch (GitAPIException gae) {
            System.out.println("\t[e] Git clone failed with error! " + gae.getClass().getCanonicalName() + " " + gae.getMessage());
        }
        return false;
    }
}
