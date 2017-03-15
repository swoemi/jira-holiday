package de.mtc.jira.holiday;

import static de.mtc.jira.holiday.ConfigMap.CF_TYPE;
import static de.mtc.jira.holiday.ConfigMap.CF_ANNUAL_LEAVE;
import static de.mtc.jira.holiday.ConfigMap.PROP_ANNUAL_LEAVE;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.jql.builder.JqlQueryBuilder;
import com.atlassian.query.Query;
import com.atlassian.velocity.VelocityManager;
import com.opensymphony.workflow.InvalidInputException;

public class Vacation extends Absence {

	private static final Logger log = LoggerFactory.getLogger(Vacation.class);

	private String type;
	boolean isHalfDay;
	private double annualLeave;
	private CustomField cfAnnualLeave;

	public Vacation(Issue issue) throws JiraValidationException {
		super(issue);
		CustomFieldManager cfm = ComponentAccessor.getCustomFieldManager();
		CustomField cfType = cfm.getCustomFieldObjectsByName(CF_TYPE).iterator().next();
		this.cfAnnualLeave = cfm.getCustomFieldObjectsByName(CF_ANNUAL_LEAVE).iterator().next();
		this.type = (String) issue.getCustomFieldValue(cfType).toString();
		this.isHalfDay = type.contains("Halbe");	
		this.annualLeave = new PropertyHelper(getUser()).getDouble(PROP_ANNUAL_LEAVE);
	}
	
	public String getType() {
		return type;
	}

	@Override
	public boolean isHalfDay() {
		return isHalfDay;
	}

	public double getAnnualLeave() {
		return annualLeave;
	}

	@Override
	public void updateFieldValues() throws JiraValidationException {
		super.updateFieldValues();
		getIssueInputParameters().addCustomFieldValue(cfAnnualLeave.getId(), String.valueOf(annualLeave));
	}

	@Override
	public void validate() throws InvalidInputException, JiraValidationException {
		if (getStartDate().compareTo(getEndDate()) > 0) {
			throw new InvalidInputException("End date must be before start date");
		}
		if (annualLeave - getVacationDaysOfThisYear() - getNumberOfWorkingDays() < 0) {
			throw new InvalidInputException("There are not enough vacation days left");
		}
	}

	public void writeVelocityComment(boolean finalApproval) throws JiraValidationException {
		VelocityManager manager = ComponentAccessor.getVelocityManager();
		Map<String, Object> contextParameters = new HashMap<>();
		AbsenceHistory<Vacation> history = initHistory();
		contextParameters.put("vacations",
				history.getVacations().stream()
				.map(t -> t.getVelocityContextParams())
				.collect(Collectors.toList()));
		contextParameters.put("vacation", getVelocityContextParams());
		contextParameters.put("currentYear", history.getCurrentYear());
		double previous = getVacationDaysOfThisYear();
		double wanted = getNumberOfWorkingDays();
		double rest = annualLeave - previous;
		double restAfter = rest - wanted;
		contextParameters.put("previous", previous);
		contextParameters.put("wanted", wanted);
		contextParameters.put("rest", rest);
		contextParameters.put("restAfter", restAfter);
		String template = finalApproval ? "comment_approved.vm" : "comment.vm";
		getIssueInputParameters().setComment(manager.getBody("templates/comment/", template, contextParameters));
	}

	@Override
	protected AbsenceHistory<Vacation> initHistory() throws JiraValidationException {
		HistoryParams<Vacation> params = new HistoryParams<Vacation>() {

			@Override
			public List<Vacation> filter(List<Issue> issues) {
				List<Vacation> vacations = new ArrayList<>();
				Calendar cal = Calendar.getInstance();
				cal.setTime(new Date());
				int year = cal.get(Calendar.YEAR);
				cal.set(year, 0, 1);
				Date startOfYear = cal.getTime();
				for (Issue issue : issues) {
					try {
						Vacation vacation = new Vacation(issue);
						if (startOfYear.compareTo(vacation.getEndDate()) > 0) {
							log.debug("Not adding entry {} > {}", startOfYear, vacation.getEndDate());
							continue;
						} else if (startOfYear.compareTo(vacation.getStartDate()) > 0) {
							vacation.setStartDate(startOfYear);
						}
						vacations.add(vacation);
					} catch (Exception e) {
						log.error("Unable to get entry for issue {}, {}", issue, e.getMessage());
					}
				}
				return vacations;
			}

			@Override
			public Query getJqlQuery() {
				Calendar cal = Calendar.getInstance();
				cal.setTime(getStartDate());
				int year = cal.get(Calendar.YEAR) - 1;
				cal.set(Calendar.YEAR, year-1);
				Date aYearBefore = cal.getTime();
				JqlQueryBuilder builder = JqlQueryBuilder.newBuilder();
				Query query = builder.where().issueType("Urlaubsantrag").and().status("APPROVED").and()
						.reporterUser(getUser().getKey()).and().createdAfter(aYearBefore).buildQuery();
				return query;
			}
		};
		
		return AbsenceHistory.getHistory(getUser(), params);
		
	}
}