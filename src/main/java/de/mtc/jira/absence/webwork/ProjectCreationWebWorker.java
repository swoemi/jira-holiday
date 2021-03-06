package de.mtc.jira.absence.webwork;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.ofbiz.core.entity.GenericEntityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.config.StatusManager;
import com.atlassian.jira.exception.CreateException;
import com.atlassian.jira.exception.PermissionException;
import com.atlassian.jira.exception.RemoveException;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.context.JiraContextNode;
import com.atlassian.jira.issue.context.ProjectContext;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenImpl;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeImpl;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItemImpl;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenTab;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeImpl;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.operation.IssueOperations;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.mail.Email;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.UserPropertyManager;
import com.atlassian.jira.web.action.JiraWebActionSupport;
import com.atlassian.jira.workflow.WorkflowUtil;
import com.atlassian.mail.queue.MailQueue;
import com.atlassian.mail.queue.SingleMailQueueItem;
import com.atlassian.mail.server.SMTPMailServer;
import com.opensymphony.workflow.FactoryException;
import com.opensymphony.workflow.loader.StepDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;

import de.mtc.jira.absence.ConfigMap;

public class ProjectCreationWebWorker extends JiraWebActionSupport {

	private static final long serialVersionUID = 1L;
	private static final Logger log = LoggerFactory.getLogger(ProjectCreationWebWorker.class);

	public final static String TEXT_SINGLE_LINE = "com.atlassian.jira.plugin.system.customfieldtypes:textfield";
	public final static String DATE_PICKER = "com.atlassian.jira.plugin.system.customfieldtypes:datepicker";
	public final static String SELECT = "com.atlassian.jira.plugin.system.customfieldtypes:select";
	public final static String READ_ONLY = "com.atlassian.jira.plugin.system.customfieldtypes:readonlyfield";

	private List<CustomField> customFields;
	private List<FieldScreen> fieldScreens;
	private Collection<ApplicationUser> users;
	private UserPropertyManager userPropertyManager;
	private String error;

	public List<FieldScreen> getFieldScreens() {
		return fieldScreens;
	}

	public List<CustomField> getCustomFields() {
		return customFields;
	}

	public String getError() {
		return error;
	}

	@Override
	protected String doExecute() throws Exception {
		log.debug("Executing main method");
		customFields = ComponentAccessor.getCustomFieldManager().getCustomFieldObjects();
		fieldScreens = new ArrayList<>(ComponentAccessor.getFieldScreenManager().getFieldScreens());
		return SUCCESS;
	}

	@Override
	public String doDefault() throws Exception {
		log.debug("Executing default method");
		return INPUT;
	}

	public void createAllFields() throws GenericEntityException {

		String description = "Automatically created for absence MTC project";
		for (String propKey : new String[] { "cf.start_date", "cf.end_date" }) {
			String name = ConfigMap.get(propKey);
			createCustomField(name, description, DATE_PICKER);
		}

		for (String propKey : new String[] { "cf.annual_leave", "cf.residual_days" }) {
			String name = ConfigMap.get(propKey);
			createCustomField(name, description, READ_ONLY);
		}

		String name = ConfigMap.get("cf.absence_type");
		CustomField cf = createCustomField(name, description, SELECT);
		if (cf != null) {
			List<FieldConfigScheme> schemes = cf.getConfigurationSchemes();
			if (schemes != null && !schemes.isEmpty()) {
				FieldConfigScheme sc = schemes.get(0);
				@SuppressWarnings("rawtypes")
				Map configs = sc.getConfigsByConfig();
				if (configs != null && !configs.isEmpty()) {
					FieldConfig config = (FieldConfig) configs.keySet().iterator().next();
					OptionsManager optionsManager = ComponentAccessor.getOptionsManager();
					List<Option> options = optionsManager.createOptions(config, null, 1L,
							Arrays.asList("Halbe Tage", "Ganze Tage"));
					log.info("Added Options {} to {}. All options {}", options, cf, optionsManager.getOptions(config));
				}
			}
		}
	}

	public CustomField createCustomField(String name, String description, String type) throws GenericEntityException {
		CustomFieldManager cfm = ComponentAccessor.getCustomFieldManager();
		CustomFieldType<?, ?> fieldType = cfm.getCustomFieldType(type);
		List<IssueType> issueTypes = new ArrayList<>(ComponentAccessor.getConstantsManager().getAllIssueTypeObjects());
		List<JiraContextNode> jiraContextNodes = ComponentAccessor.getProjectManager().getProjectObjects().stream()
				.map(project -> new ProjectContext(project.getId())).collect(Collectors.toList());

		Collection<CustomField> existing = cfm.getCustomFieldObjectsByName(name);
		if (existing != null && !existing.isEmpty()) {
			log.debug("Custom Field \"" + name + "\" already exists");
			return null;
		}
		CustomField field = cfm.createCustomField(name, description, fieldType, null, jiraContextNodes, issueTypes);
		log.debug("## Created custom Field " + field.getName() + ", " + field.getId() + ", " + field.getNameKey() + " "
				+ field.getClass());
		log.debug("Created Custom field. Name: %s, Id: %s, NameKey: %s, Class: %s", field.getName(), field.getId(),
				field.getNameKey(), field.getClass());
		addToFieldScreen(field);
		return field;
	}

	public void deleteRedundantFields() {
		CustomFieldManager cfm = ComponentAccessor.getCustomFieldManager();
		Map<String, CustomField> tempMap = new HashMap<>();
		for (CustomField cf : cfm.getCustomFieldObjects()) {
			String name = cf.getName();
			if (tempMap.get(name) == null) {
				tempMap.put(name, cf);
			} else {
				try {
					cfm.removeCustomField(cf);
				} catch (RemoveException e) {
					log.debug("Couldn't remove field " + cf.getName(), e);
				}
				log.debug("Successfully removed field " + cf.getName());
			}
		}
	}

	private void sendEmail() {
		log.debug("sending mail");
		SMTPMailServer mailServer = ComponentAccessor.getMailServerManager().getDefaultSMTPMailServer();
		Email mail = new Email("thomas.epp@mtc.berlin");
		mail.setFrom(mailServer.getDefaultFrom());
		mail.setSubject("Jira Test Mail");
		mail.setBody("This is a jira test mail");
		SingleMailQueueItem smqi = new SingleMailQueueItem(mail);
		MailQueue mailQueue = ComponentAccessor.getMailQueue();
		mailQueue.addItem(smqi);

		log.debug("Mail Server: {}, MailQueue {}", mailServer.getDefaultFrom(), mailQueue.getQueue());
		log.debug("Item being sent {}", mailQueue.getItemBeingSent());
	}

	public void createUser() throws CreateException, PermissionException {
		// ComponentAccessor.getUserManager().createUser(new
		// UserDetails("teamlead","Team Lead"));
		// ComponentAccessor.getUserManager().createUser(new
		// UserDetails("manager","Manager"));
		// ComponentAccessor.getUserManager().createUser(new
		// UserDetails("teamlead","Team Lead"));
	}

	private void addToFieldScreen(CustomField cf) {
		FieldScreenManager fieldScreenManager = ComponentAccessor.getFieldScreenManager();

		for (FieldScreen screen : fieldScreenManager.getFieldScreens()) {
			if (screen.getName().startsWith(ConfigMap.get("absence.project.key"))) {
				System.out.println(screen.getName());
				System.out.println(screen.getDescription());

				log.info("Adding Customfield {} to screen {}", cf.getName(), screen.getName());
				screen.getTab(0).addFieldScreenLayoutItem(cf.getId());
			}
		}
	}

	private void printWorkflow() {

		InputStream in = this.getClass().getClassLoader().getResourceAsStream("workflow.xml");
		String xml = null;
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
			xml = reader.lines().collect(Collectors.joining("\n"));
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		WorkflowDescriptor workflowDescriptor = null;
		try {
			workflowDescriptor = WorkflowUtil.convertXMLtoWorkflowDescriptor(xml);
		} catch (FactoryException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		StatusManager statusManager = ComponentAccessor.getComponent(StatusManager.class);
		List<StepDescriptor> stepDescriptor = workflowDescriptor.getSteps();
		Collection<Status> givenStatuses = statusManager.getStatuses();

		Map<String, String> actionNames = new HashMap<>();

		for (StepDescriptor sd : stepDescriptor) {

			Status given = null;
			for (Status status : givenStatuses) {
				if (status.getName().equals(sd.getName())) {
					given = status;
					break;
				}
			}
			if (given == null) {
				Status status = statusManager.createStatus(sd.getName(), sd.getName(), "/images/icons/pluginIcon.png");
				Map newStatus = new HashMap();
				newStatus.put("jira.status.id", status.getId());
				sd.setMetaAttributes(newStatus);
				given = status;
			}

			log.debug("Status: {} Id: {}", given.getName(), given.getId());
			actionNames.put(given.getName(), given.getId());
		}

		// WorkflowManager workflowManager =
		// ComponentAccessor.getWorkflowManager();
		// JiraWorkflow myWorkflow = new ConfigurableJiraWorkflow("absence
		// Workflow", workflowDescriptor, workflowManager);
		// workflowManager.createWorkflow(anAdminUser, myWorkflow);
		// ApplicationUser user =
		// ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser();
		// ComponentAccessor.getWorkflowManager().createWorkflow(user,
		// workflowDescriptor);
		// WorkflowUtil.

		for (Object desc : workflowDescriptor.getSteps()) {
			System.out.println(((StepDescriptor) desc).asXML());
		}
		System.out.println();
		try (BufferedWriter writer = new BufferedWriter(
				new FileWriter(new File("C:\\Users\\EMJVK\\temp\\jiraworkflow.xml")))) {

			String exportedXML = WorkflowUtil.convertDescriptorToXML(workflowDescriptor);

			writer.write(exportedXML);

		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		// try {
		//
		// DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		// dbf.setValidating(false);
		// dbf.setNamespaceAware(true);
		// dbf.setFeature("http://xml.org/sax/features/namespaces", false);
		// dbf.setFeature("http://xml.org/sax/features/validation", false);
		// dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar",
		// false);
		// dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",
		// false);
		//
		// DocumentBuilder db = dbf.newDocumentBuilder();
		// Document doc =
		// db.parse(this.getClass().getClassLoader().getResourceAsStream("workflow.xml"));
		// NodeList nl = doc.getElementsByTagName("step");
		//
		// for (int i = 0; i < nl.getLength(); i++) {
		// Element step = (Element) nl.item(i);
		// String name = step.getAttribute("name");
		// if (name == null) {
		// continue;
		// }
		// // jira.status.id
		// String id = actionNames.get(name);
		// if (id == null) {
		// continue;
		// }
		//
		// NodeList metas = step.getElementsByTagName("meta");
		// for(int j=0; j<metas.getLength(); j++) {
		// Element meta = (Element) metas.item(j);
		// if("jira.status.id".equals(meta.getAttribute("name"))) {
		// meta.setNodeValue(id);
		// }
		// }
		// }
		//
		// // write the content into xml file
		// TransformerFactory transformerFactory =
		// TransformerFactory.newInstance();
		// Transformer transformer = transformerFactory.newTransformer();
		// DOMSource source = new DOMSource(doc);
		// StreamResult result = new StreamResult(new
		// File("C:\\Users\\EMJVK\\temp\\workflow.xml"));
		//
		// transformer.transform(source, result);
		//
		// } catch (ParserConfigurationException | SAXException | IOException |
		// TransformerException e) {
		// // TODO Auto-generated catch block
		// e.printStackTrace();
		// }
	}

	private static void createFieldScreen() {
		// component dependencies, get via Constructor Injection
		FieldScreenManager fieldScreenManager = ComponentAccessor.getFieldScreenManager();
		FieldScreenSchemeManager fieldScreenSchemeManager = ComponentAccessor
				.getComponent(FieldScreenSchemeManager.class);
		FieldManager fieldManager = ComponentAccessor.getFieldManager();
		IssueTypeScreenSchemeManager issueTypeScreenSchemeManager = ComponentAccessor.getIssueTypeScreenSchemeManager();
		ConstantsManager constantsManager = ComponentAccessor.getConstantsManager();

		// create screen
		FieldScreen myScreen = new FieldScreenImpl(fieldScreenManager);
		myScreen.setName("myName");
		myScreen.setDescription("myDescription");
		myScreen.store();

		// create tab
		FieldScreenTab myTab = myScreen.addTab("myTabName");

		// add field to tab
		OrderableField myField = fieldManager.getOrderableField("assignee"); // e.g.
																				// "assignee",
																				// "customfield_12345"
		myTab.addFieldScreenLayoutItem(myField.getId());

		// add screen to scheme

		// get existing scheme...
		// FieldScreenScheme myScheme =
		// fieldScreenSchemeManager.getFieldScreenScheme(mySchemeId);
		// ... or create new scheme
		FieldScreenScheme myScheme = new FieldScreenSchemeImpl(fieldScreenSchemeManager);
		myScheme.setName("mySchemeName");
		myScheme.setDescription("mySchemeDescription");
		myScheme.store();

		// add screen
		FieldScreenSchemeItem mySchemeItem = new FieldScreenSchemeItemImpl(fieldScreenSchemeManager,
				fieldScreenManager);
		mySchemeItem.setIssueOperation(IssueOperations.CREATE_ISSUE_OPERATION); // or:
																				// EDIT_ISSUE_OPERATION,
																				// VIEW_ISSUE_OPERATION
		mySchemeItem.setFieldScreen(myScreen);
		myScheme.addFieldScreenSchemeItem(mySchemeItem);

		// create issueTypeScreenScheme
		IssueTypeScreenScheme myIssueTypeScreenScheme = new IssueTypeScreenSchemeImpl(issueTypeScreenSchemeManager,
				null);
		myIssueTypeScreenScheme.setName("myIssueTypeScreenSchemeName");
		myIssueTypeScreenScheme.setDescription("myIssueTypeScreenSchemeDescription");
		myIssueTypeScreenScheme.store();

		// add scheme to issueTypeScreenScheme
		// IssueTypeScreenSchemeEntity myEntity = new
		// IssueTypeScreenSchemeEntityImpl(
		// issueTypeScreenSchemeManager, (GenericValue) null,
		// fieldScreenSchemeManager, constantsManager);
		// IssueType issueType;
		// myEntity.setIssueTypeId(issueType != null ? issueType.getId() :
		// null); // an entity can be for all IssueTypes (-> null), or just for
		// 1
		// myEntity.setFieldScreenScheme(myScheme);
		// myIssueTypeScreenScheme.addEntity(myEntity);
		//
		// // assign to project
		// Project project = null;
		// issueTypeScreenSchemeManager.addSchemeAssociation(project,
		// myIssueTypeScreenScheme);
	}

}
