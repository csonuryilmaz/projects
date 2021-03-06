package operation;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.taskadapter.redmineapi.RedmineManager;
import com.taskadapter.redmineapi.RedmineManagerFactory;
import com.taskadapter.redmineapi.bean.Issue;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Scanner;
import model.Command;
import org.apache.commons.lang3.StringUtils;
import static org.apache.http.util.TextUtils.isEmpty;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import utilities.Properties;

/**
 *
 * @author onuryilmaz
 */
public class BeginIssue extends Operation
{
    //<editor-fold defaultstate="collapsed" desc="parameters">

    // mandatory
    private final static String PARAMETER_ISSUE = "ISSUE";
    private final static String PARAMETER_REDMINE_TOKEN = "REDMINE_TOKEN";
    private final static String PARAMETER_REDMINE_URL = "REDMINE_URL";
    private final static String PARAMETER_REPOSITORY_PATH = "REPOSITORY_PATH";
    private final static String PARAMETER_TRUNK_PATH = "TRUNK_PATH";
    private final static String PARAMETER_BRANCHES_PATH = "BRANCHES_PATH";

    // optional
    private final static String PARAMETER_BEGAN_STATUS = "BEGAN_STATUS";
    private final static String PARAMETER_WORKSPACE_PATH = "WORKSPACE_PATH";
    private final static String PARAMETER_SWITCH_FROM_ISSUE = "SWITCH_FROM_ISSUE";
    private final static String PARAMETER_CHECKOUT_IF_SWITCH_FAILS = "CHECKOUT_IF_SWITCH_FAILS";
    private final static String PARAMETER_ASSIGNEE_ID = "ASSIGNEE_ID";
    private final static String PARAMETER_REFRESH_IF_EXISTS = "REFRESH_IF_EXISTS";
    private final static String PARAMETER_IDE_PATH = "IDE_PATH";
    private final static String PARAMETER_CACHE_ENABLED = "CACHE_ENABLED";
    
    private final static String PARAMETER_REMOTE_HOST = "REMOTE_HOST";
    private final static String PARAMETER_REMOTE_USER = "REMOTE_USER";
    private final static String PARAMETER_REMOTE_PASS = "REMOTE_PASS";
    private final static String PARAMETER_REMOTE_EXEC = "REMOTE_EXEC";
    private final static String PARAMETER_REMOTE_PORT = "REMOTE_PORT";
    //</editor-fold>
    private RedmineManager redmineManager;

    public BeginIssue()
    {

	super(new String[]
	{
	    PARAMETER_ISSUE, PARAMETER_REDMINE_TOKEN, PARAMETER_REDMINE_URL, PARAMETER_REPOSITORY_PATH, PARAMETER_TRUNK_PATH, PARAMETER_BRANCHES_PATH
	}
	);
    }

    @Override
    public void execute(Properties prop) throws Exception
    {
	if (areMandatoryParametersNotEmpty(prop))
	{
	    int issueId = getParameterInt(prop, PARAMETER_ISSUE, 0);
	    String redmineAccessToken = getParameterString(prop, PARAMETER_REDMINE_TOKEN, false);
	    String redmineUrl = getParameterString(prop, PARAMETER_REDMINE_URL, false);

	    redmineManager = RedmineManagerFactory.createWithApiKey(redmineUrl, redmineAccessToken);

	    Issue issue = getIssue(redmineManager, issueId);
	    if (issue != null && isIssueAssignedTo(issue, prop))
	    {
		System.out.println("#" + issue.getId() + " - " + issue.getSubject());
		if (createBranch(prop, issue))
		{
		    updateIssueAsBegan(prop, issue);
		    createLocalWorkingCopy(prop, issue);
		    openIDE(prop, issue);
		    executeRemoteCommand(prop,issue);
		}
	    }
	}
    }

    private void updateIssueAsBegan(Properties prop, Issue issue) throws Exception
    {
	String beganStatus = getParameterString(prop, PARAMETER_BEGAN_STATUS, true);
	if (!isEmpty(beganStatus))
	{
	    System.out.println("=== Updating issue as began ");
	    int statusId = tryGetIssueStatusId(redmineManager, beganStatus);
	    if (statusId > 0)
	    {
		updateIssueStatus(redmineManager, issue, statusId);
		System.out.println("[✓]");
	    }
	}
    }

    private boolean createBranch(Properties prop, Issue issue)
    {
	System.out.println("=== Creating branch ");
	String repositoryPath = trimRight(getParameterString(prop, PARAMETER_REPOSITORY_PATH, false), "/");
	String trunkPath = trimLeft(trimRight(getParameterString(prop, PARAMETER_TRUNK_PATH, false), "/"), "/");
	String branchesPath = trimLeft(trimRight(getParameterString(prop, PARAMETER_BRANCHES_PATH, false), "/"), "/");

	String svnCommand = tryExecute("which svn");
	if (svnCommand.length() > 0)
	{
	    if (createBranchFolder(svnCommand, repositoryPath, branchesPath, issue.getId(), getParameterBoolean(prop, PARAMETER_REFRESH_IF_EXISTS)))
	    {
		return copyTrunkToBranchFolder(svnCommand, repositoryPath, trunkPath, branchesPath, issue.getId());
	    }
	}
	else
	{
	    System.err.println("Ooops! Couldn't find SVN command.");
	}

	return false;
    }

    private boolean createBranchFolder(String svnCommand, String repositoryPath, String branchesPath, Integer issueId, boolean refreshIfExists)
    {
	String branchPath = repositoryPath + "/" + branchesPath + "/" + "i" + issueId;
	if (isSvnPathExists(svnCommand, branchPath) && refreshIfExists)
	{
	    svnDelete(svnCommand, branchPath, issueId);
	}

	if (!isSvnPathExists(svnCommand, branchPath))
	{
	    return svnMkdir(svnCommand, branchPath, issueId);
	}

	System.out.println("[info] Branch folder is already exists.");
	return true;
    }

    private boolean svnMkdir(String svnCommand, String branchPath, int issueId)
    {
	String comment = "#" + issueId + " Branch folder is created.";
	String[] command = new String[]
	{
	    svnCommand, "mkdir", branchPath, "-m", comment
	};
	System.out.println(svnCommand + " mkdir " + branchPath + " -m \"" + comment + "\"");
	String[] output = execute(command);
	if (output == null || output[1].length() > 0)
	{
	    System.err.println(output[1]);
	    return false;
	}

	System.out.println(output[0]);
	return true;
    }

    private boolean svnDelete(String svnCommand, String branchPath, int issueId)
    {
	String comment = "#" + issueId + " Branch folder is deleted. It'll be refreshed.";
	String[] command = new String[]
	{
	    svnCommand, "delete", branchPath, "-m", comment
	};
	System.out.println(svnCommand + " delete " + branchPath + " -m \"" + comment + "\"");
	String[] output = execute(command);
	if (output == null || output[1].length() > 0)
	{
	    System.err.println(output[1]);
	    return false;
	}

	System.out.println(output[0]);
	return true;
    }

    private boolean copyTrunkToBranchFolder(String svnCommand, String repositoryPath, String trunkPath, String branchesPath, Integer issueId)
    {
	String target = repositoryPath + "/" + branchesPath + "/" + "i" + issueId + "/" + getProjectName(trunkPath);
	String source = repositoryPath + "/" + trunkPath;

	if (!isSvnPathExists(svnCommand, target))
	{
	    return svnCopy(svnCommand, source, target, issueId);
	}

	System.out.println("[info] Branch folder is already copied.");
	return true;
    }

    private boolean svnCopy(String svnCommand, String source, String target, int issueId)
    {
	String comment = "#" + issueId + " trunk > branch";
	String[] command = new String[]
	{
	    svnCommand, "copy", source, target, "-m", comment
	};
	System.out.println(svnCommand + " copy " + source + " " + target + " -m \"" + comment + "\"");
	String[] output = execute(command);
	if (output == null || output[1].length() > 0)
	{
	    System.err.println(output[1]);
	    return false;
	}

	System.out.println(output[0]);
	return true;
    }

    private void checkoutBranch(Properties prop, Issue issue)
    {
	String workspacePath = trimRight(getParameterString(prop, PARAMETER_WORKSPACE_PATH, false), "/");
	if (!isEmpty(workspacePath))
	{
	    String branchesPath = trimLeft(trimRight(getParameterString(prop, PARAMETER_BRANCHES_PATH, false), "/"), "/");
	    String repositoryPath = trimRight(getParameterString(prop, PARAMETER_REPOSITORY_PATH, false), "/");

	    System.out.println("=== Downloading branch into local workspace ");

	    String localPath = workspacePath + "/" + "i" + issue.getId();
	    String branchPath = repositoryPath + "/" + branchesPath + "/" + "i" + issue.getId();

	    if (deleteIfExists(localPath))
	    {
		svnCheckout(tryExecute("which svn"), branchPath, localPath);
	    }
	}
    }

    private boolean svnCheckout(String svnCommand, String source, String target)
    {
	String[] command = new String[]
	{
	    svnCommand, "checkout", "--quiet", source, target
	};
	System.out.println(svnCommand + " checkout " + source + " " + target);
	Thread thread = startFolderSizeProgress(target);
	String[] output = execute(command);
	stopFolderSizeProgress(thread, target);
	if (output == null || output[1].length() > 0)
	{
	    System.err.println(output[1]);
	    return false;
	}

	System.out.println(output[0]);
	return true;
    }

    private boolean deleteIfExists(String localPath)
    {
	File file = new File(localPath);
	if (file.exists())
	{
	    System.out.println("[info] Branch path is found in local workspace.");
	    Scanner scanner = new Scanner(System.in);
	    System.out.print("[?] Would you like to delete existing working copy for fresh checkout? (Y/N) ");
	    String answer = scanner.next();
	    if (!isEmpty(answer) && (answer.charAt(0) == 'Y' || answer.charAt(0) == 'y'))
	    {
		execute(new String[]
		{
		    "rm", "-Rf", localPath
		});
		System.out.println("[info] Local working copy is deleted.");
		return true;
	    }
	    else
	    {
		System.out.println("[info] Ok, using existing local working copy.");
		return false;
	    }
	}
	return true;
    }

    private void createLocalWorkingCopy(Properties prop, Issue issue)
    {
	if (!getParameterBoolean(prop, PARAMETER_CACHE_ENABLED))
	{
	    int switchFromIssueId = getParameterInt(prop, PARAMETER_SWITCH_FROM_ISSUE, 0);
	    if (switchFromIssueId > 0)
	    {
		switchBranch(prop, issue, switchFromIssueId);
	    }
	    else
	    {
		checkoutBranch(prop, issue);
	    }
	}
	else
	{
	    switchCache(prop, issue);
	}
    }

    private void switchBranch(Properties prop, Issue issue, int switchFromIssueId)
    {
	boolean checkoutIfSwitchFails = getParameterBoolean(prop, PARAMETER_CHECKOUT_IF_SWITCH_FAILS);
	String workspacePath = trimRight(getParameterString(prop, PARAMETER_WORKSPACE_PATH, false), "/");
	if (!isEmpty(workspacePath))
	{
	    String branchesPath = trimLeft(trimRight(getParameterString(prop, PARAMETER_BRANCHES_PATH, false), "/"), "/");
	    String repositoryPath = trimRight(getParameterString(prop, PARAMETER_REPOSITORY_PATH, false), "/");

	    System.out.println("=== Switching from old branch to new branch (#" + switchFromIssueId + " -> " + "#" + issue.getId() + ")");

	    String localPath = workspacePath + "/" + "i" + switchFromIssueId;
	    String branchPath = repositoryPath + "/" + branchesPath + "/" + "i" + issue.getId();

	    if (svnSwitch(tryExecute("which svn"), branchPath, localPath)
		    && renamePath(localPath, workspacePath + "/" + "i" + issue.getId()))
	    {
		System.out.println("[✓]");
	    }
	    else if (checkoutIfSwitchFails)
	    {
		checkoutBranch(prop, issue);
	    }
	}
    }

    private boolean svnSwitch(String svnCommand, String source, String target)
    {
	String[] command = new String[]
	{
	    svnCommand, "switch", "--quiet", "--ignore-ancestry", source, target
	};
	System.out.println(svnCommand + " switch " + source + " " + target);
	String[] output = execute(command);
	if (output == null || output[1].length() > 0)
	{
	    System.err.println(output[1]);
	    return false;
	}

	System.out.println(output[0]);
	return true;
    }

    private boolean renamePath(String srcPath, String trgPath)
    {
	File src = new File(srcPath);
	File trg = new File(trgPath);

	return src.renameTo(trg);
    }

    private boolean isIssueAssignedTo(Issue issue, Properties prop)
    {
	int assigneeId = getParameterInt(prop, PARAMETER_ASSIGNEE_ID, 0);
	if (assigneeId > 0 && issue.getAssigneeId() != assigneeId)
	{
	    System.out.println("Ooops! " + "#" + issue.getId() + " - " + issue.getSubject() + " is assigned to " + issue.getAssigneeName() + ".");
	    return false;
	}

	return true;
    }

    private void openIDE(Properties prop, Issue issue)
    {
	String idePath = getParameterString(prop, PARAMETER_IDE_PATH, false);
	String workspacePath = trimRight(getParameterString(prop, PARAMETER_WORKSPACE_PATH, false), "/");
	if (!isEmpty(idePath) && !isEmpty(workspacePath))
	{
	    Scanner scanner = new Scanner(System.in);
	    System.out.print("[?] Would you like open to IDE for development? (Y/N) ");
	    String answer = scanner.next();
	    if (!isEmpty(answer) && (answer.charAt(0) == 'Y' || answer.charAt(0) == 'y'))
	    {
		File projectPath = new File(workspacePath + "/" + "i" + issue.getId()
			+ "/" + new File(trimLeft(trimRight(getParameterString(prop, PARAMETER_TRUNK_PATH, false), "/"), "/")).getName());
		if (projectPath.exists())
		{
		    Command cmd = new Command(idePath.contains("%s") ? String.format(idePath, projectPath.getAbsolutePath())
			    : idePath + " " + projectPath.getAbsolutePath());
		    System.out.println("[*] Opening IDE ...");
		    System.out.println(cmd.getCommandString());
		    cmd.executeNoWait();
		}
		else
		{
		    System.out.print("Ooops! " + "Project not found in path:" + projectPath.getAbsolutePath());
		}
	    }
	}
    }

    private boolean svnUpdate(String svnCommand, String localPath)
    {
	String command = svnCommand + " up " + localPath;
	System.out.println(command);
	String[] output = execute(command);
	if (output == null || output[1].length() > 0)
	{
	    System.err.println(output[1]);
	    return false;
	}

	System.out.println(output[0]);
	return true;
    }

    private boolean copy(String sourcePath, String targetPath)
    {
	String command = "cp -r " + sourcePath + " " + targetPath;
	System.out.println(command);
	String[] output = execute(command);
	if (output == null || output[1].length() > 0)
	{
	    System.err.println(output[1]);
	    return false;
	}

	System.out.println(output[0]);
	return true;
    }

    private void switchCache(Properties prop, Issue issue)
    {
	String cachePath = getWorkingDirectory() + "/" + "cache";
	createFolder(cachePath);

	String repositoryPath = trimRight(getParameterString(prop, PARAMETER_REPOSITORY_PATH, false), "/");
	cachePath += "/" + new File(repositoryPath).getName();
	createFolder(cachePath);

	String trunk = "/" + trimLeft(trimRight(getParameterString(prop, PARAMETER_TRUNK_PATH, false), "/"), "/");
	File cache = new File(cachePath + trunk);
	if (!cache.exists())
	{
	    svnCheckout(tryExecute("which svn"), repositoryPath + trunk, cache.getAbsolutePath());
	}
	if (svnUpdate(tryExecute("which svn"), cache.getAbsolutePath()))
	{
	    String workspacePath = trimRight(getParameterString(prop, PARAMETER_WORKSPACE_PATH, false), "/");
	    if (!isEmpty(workspacePath))
	    {
		String branchPath = workspacePath + "/" + "i" + issue.getId();
		if (deleteIfExists(branchPath))
		{
		    if (createFolder(branchPath)) {
                        branchPath += "/" + cache.getName();
                        copy(cache.getAbsolutePath(), branchPath);
                        repositoryPath += "/" + trimLeft(trimRight(getParameterString(prop, PARAMETER_BRANCHES_PATH, false), "/"), "/");
                        repositoryPath += "/" + "i" + issue.getId() + "/" + cache.getName();
                        if (svnSwitch(tryExecute("which svn"), repositoryPath, branchPath)) {
                            System.out.println("[✓] Local working copy is ready. \\o/");
                        }
                    } else {
                        System.err.println("[e] Local working copy could not be created!");
                        System.err.println("[e] Please check whether workspace path exists.");
                        System.err.println("[e] " + branchPath);
                        System.exit(-1);
                    }
		}
	    }
	}
    }
    
    private void executeRemoteCommand(Properties prop, Issue issue) throws JSchException, IOException, InterruptedException
    {
	String sshHost = getParameterString(prop, PARAMETER_REMOTE_HOST, false);
	String sshUser = getParameterString(prop, PARAMETER_REMOTE_USER, false);
	String sshPass = getParameterString(prop, PARAMETER_REMOTE_PASS, false);
	String sshExec = getParameterString(prop, PARAMETER_REMOTE_EXEC, false);

	byte validValueCount = 0;
	validValueCount += StringUtils.isBlank(sshHost) ? 0 : 1;
	validValueCount += StringUtils.isBlank(sshUser) ? 0 : 1;
	validValueCount += StringUtils.isBlank(sshPass) ? 0 : 1;
	validValueCount += StringUtils.isBlank(sshExec) ? 0 : 1;
	if (validValueCount > 0) {
	    System.out.println("[*] Remote command execution ...");
	    if (validValueCount < 4) {
		System.out.println("[e] Missing remote parameters! "
			+ "Please, set all values for REMOTE_HOST, REMOTE_USER, REMOTE_PASS, REMOTE_EXEC parameters.");
	    }
	    else {
		int sshPort = getParameterInt(prop, PARAMETER_REMOTE_PORT, 22);

		System.out.println("[i] \tHost: " + sshHost + getHostIPAddress(sshHost));
		System.out.println("[i] \tUser: " + sshUser);
		System.out.println("[i] \tPort: " + sshPort);
		System.out.println("[i] \tExec: " + sshExec);
		
		Velocity.init();
		VelocityContext context = new VelocityContext();
		context.put("issue", issue.getId());
		StringWriter writer = new StringWriter();
		Velocity.evaluate(context, writer, "TemplateName", sshExec);
		sshExec = writer.toString();

		JSch jsch = new JSch();
		Session session = jsch.getSession(sshUser, sshHost, sshPort);
		session.setPassword(sshPass);
		java.util.Properties config = new java.util.Properties();
		config.put("StrictHostKeyChecking", "no");
		session.setConfig(config);
		System.out.println("[*] \tConnecting ...");
		session.connect();
		System.out.println("[i] \tConnected. \\o/");

		ChannelExec channelExec = (ChannelExec) session.openChannel("exec");
		System.out.println("[*] \tExecuting command ...");
		channelExec.setCommand("exec 2>&1 && " + sshExec);
		InputStream stream = channelExec.getInputStream();
		channelExec.connect();

		byte[] tmp = new byte[1024];
		while (true) {
		    while (stream.available() > 0) {
			int i = stream.read(tmp, 0, 1024);
			if (i < 0) {
			    break;
			}
			System.out.print(new String(tmp, 0, i));
		    }
		    if (channelExec.isClosed()) {
			if (stream.available() > 0) {
			    continue;
			}
			break;
		    }
		    Thread.sleep(500);
		}

		int exitStatus = channelExec.getExitStatus();
		System.out.println("[i] \tCommand exit with " + exitStatus + ".");
		if (exitStatus == 0) {
		    System.out.println("[✓] Done successfully.");
		} else {
		    System.out.println("[e] Remote execution failed! Inspect command logs.");
		}
		channelExec.disconnect();
		session.disconnect();
	    }
	}
    }

}
