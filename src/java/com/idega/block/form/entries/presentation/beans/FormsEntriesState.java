package com.idega.block.form.entries.presentation.beans;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.faces.context.FacesContext;
import javax.faces.model.SelectItem;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;

import com.idega.block.form.data.UploadedFile;
import com.idega.block.form.data.UploadedFileResolver;
import com.idega.block.form.data.UploadedFileWriter;
import com.idega.block.form.entries.presentation.UIFormsEntriesViewer;
import com.idega.block.form.presentation.FormViewer;
import com.idega.block.pdf.business.PDFWriterProvider;
import com.idega.util.CoreConstants;
import com.idega.util.StringUtil;
import com.idega.util.expression.ELUtil;
import com.idega.xformsmanager.business.Form;
import com.idega.xformsmanager.business.PersistenceManager;
import com.idega.xformsmanager.business.Submission;
import com.idega.xformsmanager.business.XFormPersistenceType;
import com.idega.xformsmanager.util.FormManagerUtil;

/**
 *
 * @author <a href="civilis@idega.com">Vytautas Čivilis</a>
 * @version $Revision: 1.8 $
 *
 *          Last modified: $Date: 2008/11/13 07:16:58 $ by $Author: valdas $
 *
 */
@Scope("request")
@Service("formsEntries")
public class FormsEntriesState implements Serializable {

	private static final long serialVersionUID = 7398164884900011980L;
	private String facetDisplayed;
	private String submissionFacetDisplayed;
	private String submissionId;
	private static final String submissionIdParam = FormViewer.submissionIdParam;
	private static final String formIdParam = "formId";
	private Long formId;

	@Autowired private UploadedFileResolver fileResolver;
	@Autowired
	@XFormPersistenceType("slide")
	private transient PersistenceManager persistenceManager;
	@Autowired(required = false) private PDFWriterProvider pdfWriter;

	public List<FormSubmissionEntry> getFormsEntries() {

		List<Submission> submissions;

		if(getFormId() == null)
			submissions = getPersistenceManager()
			.getAllStandaloneFormsSubmissions();
		else
			submissions = getPersistenceManager().getFormsSubmissions(getFormId());

		ArrayList<FormSubmissionEntry> entries = new ArrayList<FormSubmissionEntry>(
				submissions.size());

		for (Submission submission : submissions) {

			entries.add(new FormSubmissionEntry(submission, submission
					.getXform()));
		}

		return entries;
	}

	public boolean isEntriesRendered() {

		return UIFormsEntriesViewer.entriesFacet.equals(getFacetDisplayed());
	}

	public boolean isSubmissionView() {

		return UIFormsEntriesViewer.formSubmissionFacet.equals(getSubmissionFacetDisplayed());
	}

	public boolean isSourceView() {

		return UIFormsEntriesViewer.formSubmissionSourceFacet.equals(getSubmissionFacetDisplayed());
	}

	public boolean isFormSubmissionRendered() {

		return UIFormsEntriesViewer.formSubmissionFacet
				.equals(getFacetDisplayed());
	}

	public Class<?> getDocumentDownloader() {
		if (pdfWriter == null) {
			Logger.getLogger(FormsEntriesState.class.getName()).log(Level.SEVERE, "Error getting Spring bean for: " + pdfWriter.getClass() +
																															", add missing dependency!");
			return null;
		}

		return pdfWriter.getPDFWriterClass();
	}

	String getFacetDisplayed() {

		String submissionIdStr = FacesContext.getCurrentInstance()
				.getExternalContext().getRequestParameterMap().get(
						submissionIdParam);

		if (!StringUtil.isEmpty(submissionIdStr)) {
			facetDisplayed = UIFormsEntriesViewer.formSubmissionFacet;
			submissionFacetDisplayed = UIFormsEntriesViewer.formSubmissionFacet;
		} else if (facetDisplayed == null) {
			facetDisplayed = UIFormsEntriesViewer.entriesFacet;
		}

		return facetDisplayed;
	}

	void setFacetDisplayed(String facetDisplayed) {
		this.facetDisplayed = facetDisplayed;
	}

	public void showEntries() {
		setFacetDisplayed(UIFormsEntriesViewer.entriesFacet);
	}

	public void viewSource() {
		setSubmissionFacetDisplayed(UIFormsEntriesViewer.formSubmissionSourceFacet);
	}

	public void viewSubmission() {
		setSubmissionFacetDisplayed(UIFormsEntriesViewer.formSubmissionFacet);
	}

	PersistenceManager getPersistenceManager() {

		if (persistenceManager == null)
			ELUtil.getInstance().autowire(this);

		return persistenceManager;
	}

	void setPersistenceManager(PersistenceManager persistenceManager) {
		this.persistenceManager = persistenceManager;
	}

	public String getSubmissionFacetDisplayed() {
		return submissionFacetDisplayed;
	}

	public void setSubmissionFacetDisplayed(String submissionFacetDisplayed) {
		this.submissionFacetDisplayed = submissionFacetDisplayed;
	}

	public String getSubmissionSource() {

		Long submissionId = new Long(getSubmissionId());

		Submission submission = getPersistenceManager().getSubmission(submissionId);

		Document submissionDocument = submission.getSubmissionDocument();

		String serialized;

		try {
			serialized = FormManagerUtil.serializeDocument(submissionDocument);

		} catch (IOException e) {
			Logger.getLogger(getClass().getName()).log(Level.SEVERE, "Exception while serializing submission document. submission id = "+submission.getSubmissionId(), e);
			serialized = CoreConstants.EMPTY;
		}

		return serialized;
	}

	public String getSubmissionId() {

		String submissionIdStr = FacesContext.getCurrentInstance()
		.getExternalContext().getRequestParameterMap().get(
				submissionIdParam);

		if(!StringUtil.isEmpty(submissionIdStr)) {
			setSubmissionId(submissionIdStr);
		}

		return submissionId;
	}

	public void setSubmissionId(String submissionId) {
		this.submissionId = submissionId;
	}

	public List<SelectItem> getForms() {

		List<Form> forms = getPersistenceManager().getStandaloneForms();

		ArrayList<SelectItem> formsItems = new ArrayList<SelectItem>(forms.size());

		formsItems.add(new SelectItem(CoreConstants.EMPTY, "All forms"));

		for (Form form : forms) {

			formsItems.add(new SelectItem(form.getFormId(), form.getDisplayName()));
		}

		return formsItems;
	}

	public Long getFormId() {

		if(formId == null) {

			String formIdStr = FacesContext.getCurrentInstance()
			.getExternalContext().getRequestParameterMap().get(
					formIdParam);

			if(!StringUtil.isEmpty(formIdStr))
				setFormId(new Long(formIdStr));
		}

		return formId;
	}

	public void setFormId(Long formId) {
		this.formId = formId;
	}

	public List<UploadedFile> getAttachments() {
		Long submissionId = new Long(getSubmissionId());
		Submission sub = getPersistenceManager().getSubmission(submissionId);
		List<UploadedFile> files = getFileResolver().resolveUploadedList(sub.getSubmissionDocument());

		return files;
	}

	public Class<?> getDownloadWriter() {
		return UploadedFileWriter.class;
	}

	private UploadedFileResolver getFileResolver() {
		return fileResolver;
	}

	public PDFWriterProvider getPdfWriter() {
		return pdfWriter;
	}

	public void setPdfWriter(PDFWriterProvider pdfWriter) {
		this.pdfWriter = pdfWriter;
	}
}