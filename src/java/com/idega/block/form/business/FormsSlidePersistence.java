package com.idega.block.form.business;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;

import org.chiba.xml.dom.DOMUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.w3c.dom.Document;

import com.idega.block.form.bean.SubmissionDataBean;
import com.idega.block.form.data.XForm;
import com.idega.block.form.data.XFormSubmission;
import com.idega.block.form.data.dao.XFormsDAO;
import com.idega.block.form.event.FormSavedEvent;
import com.idega.business.IBOLookup;
import com.idega.business.IBOLookupException;
import com.idega.core.file.util.MimeTypeUtil;
import com.idega.core.idgenerator.business.UUIDGenerator;
import com.idega.core.persistence.Param;
import com.idega.idegaweb.IWApplicationContext;
import com.idega.idegaweb.IWMainApplication;
import com.idega.slide.business.IWSlideService;
import com.idega.user.business.UserBusiness;
import com.idega.user.data.User;
import com.idega.util.CoreConstants;
import com.idega.util.IOUtil;
import com.idega.util.StringUtil;
import com.idega.util.datastructures.map.MapUtil;
import com.idega.util.expression.ELUtil;
import com.idega.util.xml.XmlUtil;
import com.idega.xformsmanager.business.DocumentManager;
import com.idega.xformsmanager.business.DocumentManagerFactory;
import com.idega.xformsmanager.business.Form;
import com.idega.xformsmanager.business.FormLockException;
import com.idega.xformsmanager.business.InvalidSubmissionException;
import com.idega.xformsmanager.business.PersistedFormDocument;
import com.idega.xformsmanager.business.PersistenceManager;
import com.idega.xformsmanager.business.Submission;
import com.idega.xformsmanager.business.SubmittedDataBean;
import com.idega.xformsmanager.business.XFormPersistenceType;
import com.idega.xformsmanager.business.XFormState;
import com.idega.xformsmanager.component.FormDocument;

/**
 * @author <a href="mailto:civilis@idega.com">Vytautas Čivilis</a>
 * @version $Revision: 1.44 $ Last modified: $Date: 2009/06/08 08:34:45 $ by $Author: valdas $
 */

@XFormPersistenceType("slide")
@Scope(BeanDefinition.SCOPE_SINGLETON)
@Repository("xformsPersistenceManager")
@Transactional(readOnly = true)
public class FormsSlidePersistence implements PersistenceManager {

	private static final String slideStorageType = "slide";
	private static final String standaloneFormType = "standalone";
	private static final String submissionFileName = "submission.xml";

	private final Logger logger;
	private IWApplicationContext iwac;
	private XFormsDAO xformsDAO;
	private DocumentManagerFactory documentManagerFactory;

	public static final String FORMS_PATH = "/files/forms";
	public static final String STANDALONE_FORMS_PATH = FORMS_PATH  + "/standalone";
	public static final String FORMS_FILE_EXTENSION = ".xhtml";
	public static final String SUBMITTED_DATA_PATH = "/files/forms/submissions";

	public FormsSlidePersistence() {
		logger = Logger.getLogger(getClass().getName());
	}

	protected Logger getLogger() {
		return logger;
	}

	protected String getFormResourcePath(String formType, String formIdentifier, String formBasePath, boolean withFile) {
		StringBuilder b = StringUtil.isEmpty(formBasePath) ?
				new StringBuilder(FORMS_PATH).append(CoreConstants.SLASH).append(formType).append(CoreConstants.SLASH) :
				new StringBuilder(formBasePath);

		if (withFile) {
			if (!b.toString().endsWith(CoreConstants.SLASH)) {
				b.append(CoreConstants.SLASH);
			}

			b = b.append(formIdentifier).append(FORMS_FILE_EXTENSION);
		}
		return b.toString();
	}

	@Override
	@Transactional(readOnly = true)
	public PersistedFormDocument loadForm(Long formId) {
		XForm xform = getXformsDAO().find(XForm.class, formId);

		String formPath = xform.getFormStorageIdentifier();
		Document xformsDoc = loadXMLResourceFromSlide(formPath);

		PersistedFormDocument formDoc = new PersistedFormDocument();
		formDoc.setFormId(formId);
		formDoc.setFormType(xform.getFormType());
		formDoc.setXformsDocument(xformsDoc);
		formDoc.setVersion(xform.getVersion());

		return formDoc;
	}

	@Override
	public PersistedFormDocument loadPopulatedForm(String submissionUUID) {
		return loadPopulatedForm(submissionUUID, false);
	}

	@Override
	@Transactional(readOnly = true)
	public PersistedFormDocument loadPopulatedForm(String submissionUUID, boolean pdfView) {
		XFormSubmission xformSubmission = getXformsDAO().getSubmissionBySubmissionUUID(submissionUUID);
		final PersistedFormDocument formDoc;

		if (xformSubmission != null) {
			if (xformSubmission.getIsValidSubmission() != null && !xformSubmission.getIsValidSubmission()) {
				throw new InvalidSubmissionException("The submission is invalid, submissionUUID=" + submissionUUID);
			}

			XForm xform = xformSubmission.getXform();

			String formPath = xform.getFormStorageIdentifier();
			Document xformsDoc = loadXMLResourceFromSlide(formPath);

			Document submissionDoc = xformSubmission.getSubmissionDocument();

			// TODO: load with submitted data

			DocumentManager documentManager = getDocumentManagerFactory().newDocumentManager(null);
			com.idega.xformsmanager.business.Document form = documentManager.openFormLazy(xformsDoc);

			form.populateSubmissionDataWithXML(submissionDoc, true);
			form.setReadonly(xformSubmission.getIsFinalSubmission());
			if (pdfView) {
				form.setPdfForm(Boolean.TRUE);
			}
			xformsDoc = form.getXformsDocument();

			formDoc = new PersistedFormDocument();
			formDoc.setFormId(xform.getFormId());
			formDoc.setFormType(xform.getFormType());
			formDoc.setXformsDocument(xformsDoc);
			formDoc.setVersion(xform.getVersion());

		} else {
			logger.log(Level.WARNING, "No submission found by submissionId provided=" + submissionUUID);
			formDoc = null;
		}

		return formDoc;
	}

	protected Document loadXMLResourceFromSlide(String resourcePath) {
		InputStream stream = null;
		try {
			IWSlideService service = getIWSlideService();

			stream = service.getInputStream(resourcePath);

			DocumentBuilder docBuilder = XmlUtil.getDocumentBuilder();
			Document resourceDocument = docBuilder.parse(stream);

			return resourceDocument;
		} catch (Exception e) {
			throw new RuntimeException("Error loading file: " + resourcePath, e);
		} finally {
			IOUtil.closeInputStream(stream);
		}
	}

	protected void saveExistingXFormsDocumentToSlide(Document xformsDoc, String path) {
		ByteArrayOutputStream out = null;
		InputStream in = null;
		try {
			IWSlideService service = getIWSlideService();

			out = new ByteArrayOutputStream();
			DOMUtil.prettyPrintDOM(xformsDoc, out);
			in = new ByteArrayInputStream(out.toByteArray());
			int lastslash = path.lastIndexOf(CoreConstants.SLASH);
			String parentPath = path.substring(0, lastslash + 1);
			String fileName = path.substring(lastslash + 1);
			if (!service.uploadFile(parentPath, fileName, MimeTypeUtil.MIME_TYPE_XML, in)) {
				throw new RuntimeException("Unable to upload XForm to repository: " + path);
			}
		} catch (IllegalArgumentException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException("Error saving XForm to Slide: " + path, e);
		} finally {
			IOUtil.close(out);
			IOUtil.close(in);
		}
	}

	protected String saveXFormsDocumentToSlide(Document xformsDoc, String formIdentifier, String formType, String formBasePath) {
		ByteArrayOutputStream out = null;
		InputStream in = null;
		try {
			String formResourcePath = getFormResourcePath(formType, formIdentifier, formBasePath, true);

			String pathToFileFolder = getFormResourcePath(formType, formIdentifier, formBasePath, false);
			if (!pathToFileFolder.endsWith(CoreConstants.SLASH)) {
				pathToFileFolder = pathToFileFolder.concat(CoreConstants.SLASH);
			}
			String fileName = formIdentifier + FORMS_FILE_EXTENSION;

			IWSlideService slideService = getIWSlideService();
			out = new ByteArrayOutputStream();
			DOMUtil.prettyPrintDOM(xformsDoc, out);
			in = new ByteArrayInputStream(out.toByteArray());
			if (slideService.uploadFile(pathToFileFolder, fileName, MimeTypeUtil.MIME_TYPE_XML, in)) {
				return formResourcePath;
			}
			throw new RuntimeException("Error uploading XForm '" + formIdentifier + "' to repository: " + pathToFileFolder + fileName);
		} catch (Exception e) {
			throw new RuntimeException("Error saving XForm '" + formIdentifier + "' to Slide", e);
		} finally {
			IOUtil.close(out);
			IOUtil.close(in);
		}
	}

	@Override
	@Transactional(readOnly = false)
	public PersistedFormDocument saveForm(FormDocument document) throws IllegalAccessException {
		return saveForm(document, null);
	}

	@Override
	@Transactional(readOnly = false)
	public PersistedFormDocument saveForm(FormDocument document, String storeBasePath) throws IllegalAccessException {
		if (document == null)
			throw new NullPointerException("FormDocument not provided");

		Long formId = document.getFormId();
		String defaultFormName = document.getFormTitle().getString(document.getDefaultLocale());
		PersistedFormDocument formDocument = new PersistedFormDocument();

		if (formId == null) {
			String formSlideId = generateFormId(defaultFormName);
			String formType = document.getFormType() == null ? standaloneFormType : document.getFormType();

			Document xformsDocument = document.getXformsDocument();

			if (storeBasePath != null)
				storeBasePath = CoreConstants.PATH_FILES_ROOT + storeBasePath;

			String formPath = saveXFormsDocumentToSlide(xformsDocument, formSlideId, formType, storeBasePath);

			Integer version = 1;

			XForm xform = new XForm();
			xform.setDisplayName(defaultFormName);
			xform.setDateCreated(new Date());
			xform.setFormState(XFormState.FLUX);
			xform.setFormStorageIdentifier(formPath);
			xform.setFormStorageType(slideStorageType);
			xform.setFormType(formType);
			xform.setVersion(version);

			getXformsDAO().persist(xform);
			formId = xform.getFormId();

			formDocument.setFormId(formId);
			formDocument.setFormType(formType);
			formDocument.setXformsDocument(xformsDocument);
			formDocument.setVersion(version);
		} else {
			XForm xform = getXformsDAO().find(XForm.class, formId);

			if (xform.getFormState() != XFormState.FIRM) {
				xform.setVersion(xform.getVersion() + 1);
			} else {
				String formSlideId = generateFormId(defaultFormName);
				String formType = document.getFormType() == null ? standaloneFormType  : document.getFormType();

				Document xformsDocument = document.getXformsDocument();

				// saving in a new file
				String formPath = saveXFormsDocumentToSlide(xformsDocument, formSlideId, formType, null);
				xform.setFormStorageIdentifier(formPath);
			}
			String formPath = xform.getFormStorageIdentifier();
			Document xformsDocument = document.getXformsDocument();
			saveExistingXFormsDocumentToSlide(xformsDocument, formPath);

			xform.setDisplayName(defaultFormName);
			getXformsDAO().merge(xform);

			formDocument.setFormId(formId);
			formDocument.setFormType(xform.getFormType());
			formDocument.setXformsDocument(xformsDocument);
			formDocument.setVersion(xform.getVersion());
		}

		return formDocument;
	}

	@Override
	@Transactional(readOnly = false)
	public PersistedFormDocument saveAllVersions(final FormDocument document, final Long parentId) throws IllegalAccessException {
		PersistedFormDocument persistedFormDocument = saveForm(document);

		Long formId = document.getFormId();
		String defaultFormName = document.getFormTitle().getString(document.getDefaultLocale());

		List<XForm> previousVersions = getXformsDAO().getAllVersionsByParentId(parentId);
		if (previousVersions != null)
			previousVersions = new ArrayList<XForm>(previousVersions);

		//	Double checking if all forms were found...
		try {
			XForm xform = getXformsDAO().find(XForm.class, formId);
			String storageIdentifier = xform.getFormStorageIdentifier();
			storageIdentifier = storageIdentifier.split(CoreConstants.MINUS)[0];
			String type = xform.getFormType();
			List<XForm> formsByQuery = getXformsDAO().getXFormsByNameAndStorageIndetifierAndType(defaultFormName, storageIdentifier, type);
			if (previousVersions == null)
				previousVersions = formsByQuery;
			else if (formsByQuery != null)
				previousVersions.addAll(formsByQuery);
		} catch (Exception e) {
			getLogger().log(Level.WARNING, "Error getting previous versions for XForm: " + formId, e);
		}

		if (!formId.equals(parentId))
			previousVersions.add(getXformsDAO().find(XForm.class, parentId));

		for (XForm xform : previousVersions) {
			if (!xform.getFormId().equals(formId)) {
				if (xform.getFormState() != XFormState.FIRM) {
					xform.setVersion(xform.getVersion() + 1);
				} else {
					String formSlideId = generateFormId(defaultFormName);
					String formType = document.getFormType() == null ? standaloneFormType : document.getFormType();

					Document xformsDocument = document.getXformsDocument();

					// saving in a new file
					String formPath = saveXFormsDocumentToSlide(xformsDocument, formSlideId, formType, null);
					xform.setFormStorageIdentifier(formPath);
				}
				String formPath = xform.getFormStorageIdentifier();
				Document xformsDocument = document.getXformsDocument();
				saveExistingXFormsDocumentToSlide(xformsDocument, formPath);

				xform.setDisplayName(defaultFormName);
				getXformsDAO().merge(xform);
			}
		}
		// returning current version data
		return persistedFormDocument;
	}

	@Override
	@Transactional(readOnly = false)
	public PersistedFormDocument takeForm(Long formId) {
		XForm xform = getXformsDAO().find(XForm.class, formId);
		PersistedFormDocument formDocument = new PersistedFormDocument();

		Document xformsDocument;

		if (xform.getFormState() == XFormState.FIRM) {
			getLogger().log(Level.WARNING, "TakeForm on firm form. Is this the expected behavior?");

			xformsDocument = loadXMLResourceFromSlide(xform.getFormStorageIdentifier());
		} else if (xform.getFormState() == XFormState.FLUX) {
			// making firm

			XForm existingFirmXForm = getXformsDAO().getXFormByParentVersion(xform, xform.getVersion(), XFormState.FIRM);

			if (existingFirmXForm != null) {
				xformsDocument = loadXMLResourceFromSlide(existingFirmXForm.getFormStorageIdentifier());
				xform = existingFirmXForm;
			} else {
				// here we take flux state form, get it's contents, and store it as new firm form
				String formStorageIdentifier = xform.getFormStorageIdentifier();
				xformsDocument = loadXMLResourceFromSlide(formStorageIdentifier);
				String formSlideId = generateFormId(xform.getDisplayName());
				String formType = xform.getFormType();

				// storing into the same folder as the parent form
				String formBasePath = formStorageIdentifier.substring(0, formStorageIdentifier.lastIndexOf(CoreConstants.SLASH) + 1);

				String formPath = saveXFormsDocumentToSlide(xformsDocument, formSlideId, formType, formBasePath);

				XForm newFirmForm = new XForm();
				newFirmForm.setDateCreated(xform.getDateCreated());
				newFirmForm.setDisplayName(xform.getDisplayName());
				newFirmForm.setFormParent(xform);
				newFirmForm.setFormState(XFormState.FIRM);
				newFirmForm.setFormStorageType(slideStorageType);
				newFirmForm.setFormStorageIdentifier(formPath);
				newFirmForm.setFormType(formType);
				newFirmForm.setVersion(xform.getVersion());

				getXformsDAO().persist(newFirmForm);

				xform = newFirmForm;
			}
		} else
			throw new IllegalStateException("XForm state not supported by slide persistence manager. State: " + xform.getFormState());

		formDocument.setFormId(xform.getFormId());
		formDocument.setFormType(xform.getFormType());
		formDocument.setXformsDocument(xformsDocument);
		formDocument.setVersion(xform.getVersion());

		return formDocument;
	}

	public void duplicateForm(String formId, String new_title_for_default_locale) throws Exception {

		if (true)
			throw new UnsupportedOperationException("Not supported yet, make this call from document manager");

		// Document xformsDoc = loadFormNoLock(formId);
		// com.idega.xformsmanager.business.Document document =
		// getDocumentManagerFactory().newDocumentManager(iwma).openForm(xformsDoc);

		// if(newTitle == null)
		// newTitle = new
		// LocalizedStringBean("copy_"+document.getFormTitle().getString(new
		// Locale("en")));

		// BlockFormUtil.putDefaultTitle(xformsDoc, newTitleForDefaultLocale);
		//
		// formId = generateFormId(newTitleForDefaultLocale);
		// saveForm(formId, xformsDoc, false);
	}

	public void removeForm(String form_id, boolean remove_submitted_data) throws FormLockException, Exception {

		if (true)
			throw new UnsupportedOperationException();

		/*
		 * if(form_id == null || form_id.equals("")) throw new
		 * NullPointerException("Form id not provided");
		 *
		 * WebdavResource simpleResource = loadFormResource(FORMS_PATH, form_id,
		 * true); WebdavExtendedResource resource =
		 * getWebdavExtendedResource(simpleResource.getPath());
		 *
		 * if(resource == null) throw new
		 * Exception("Form with id: "+form_id+" couldn't be loaded from webdav"
		 * );
		 *
		 * Exception e = null; try {
		 * resource.deleteMethod(resource.getParentPath());
		 *
		 * } catch (Exception some_e) { logger.log(Level.SEVERE,
		 * "Exception occured while deleting form document: "+form_id, some_e);
		 * e = some_e; }
		 *
		 * if(remove_submitted_data) {
		 *
		 * try { resource = loadSubmittedDataFolderResource(form_id);
		 *
		 * if(resource == null) return;
		 *
		 * resource.deleteMethod();
		 *
		 *
		 * } catch (Exception some_e) {
		 *
		 * logger.log(Level.SEVERE,
		 * "Exception occured while deleting form's submitted data for form document: "
		 * +form_id, some_e);
		 *
		 * // more imporant to display first error if(e == null) e = some_e; } }
		 *
		 * if(e != null) throw e;
		 */
	}

	@Override
	public List<Form> getStandaloneForms() {
		String formType = standaloneFormType;
		String formStorageType = slideStorageType;

		List<Form> xforms = getXformsDAO().getAllXFormsByTypeAndStorageType(formType, formStorageType, XFormState.FLUX);
		return xforms;
	}

	public List<Submission> getStandaloneFormSubmissions(long formId) {
		String formType = standaloneFormType;
		String formStorageType = slideStorageType;

		List<Submission> subs = getXformsDAO().getSubmissionsByTypeAndStorageType(formType, formStorageType, formId);
		return subs;
	}

	@Override
	public List<Submission> getAllStandaloneFormsSubmissions() {
		List<Submission> submissions = getXformsDAO().getResultListByInlineQuery(
			"select submissions from com.idega.block.form.data.XForm xforms inner join xforms." + XForm.xformSubmissionsProperty + " submissions where xforms."
				+ XForm.formTypeProperty + " = :" + XForm.formTypeProperty + " and submissions." + XFormSubmission.isFinalSubmissionProperty + " = :"
		        + XFormSubmission.isFinalSubmissionProperty,
		     Submission.class,
		     new Param(XForm.formTypeProperty, standaloneFormType),
		     new Param(XFormSubmission.isFinalSubmissionProperty, true));

		return submissions;
	}

	@Override
	public List<Submission> getFormsSubmissions(long formId) {

		// List<Submission> submissions =
		// getXformsDAO().getResultListByInlineQuery("select submissions from "
		// +
		// "com.idega.block.form.data.XForm xforms inner join xforms."+XForm.xformSubmissionsProperty+" submissions "
		// +
		// "where xforms."+XForm.formIdProperty+" = :"+XForm.formIdProperty+" and "
		// +
		// "submissions."+XFormSubmission.isFinalSubmissionProperty+" = :"+XFormSubmission.isFinalSubmissionProperty,
		// Submission.class,
		// new Param(XForm.formIdProperty, formId),
		// new Param(XFormSubmission.isFinalSubmissionProperty, true)
		// );
		//
		// return submissions;

		return getStandaloneFormSubmissions(formId);
	}

	@Override
	public Submission getSubmission(long submissionId) {
		XFormSubmission submission = getXformsDAO().find(XFormSubmission.class, submissionId);
		return submission;
	}

	public String getSubmittedDataResourcePath(String formId, String submittedDataFilename) {
		return new StringBuilder(SUBMITTED_DATA_PATH).append(CoreConstants.SLASH).append(formId).append(CoreConstants.SLASH).append(submittedDataFilename).toString();
	}

	protected IWSlideService getIWSlideService() throws IBOLookupException {
		try {
			return IBOLookup.getServiceInstance(getIWApplicationContext(), IWSlideService.class);
		} catch (IBOLookupException e) {
			logger.log(Level.SEVERE, "Error getting IWSlideService");
			throw e;
		}
	}

	private synchronized IWApplicationContext getIWApplicationContext() {
		if (iwac == null)
			iwac = IWMainApplication.getDefaultIWApplicationContext();

		return iwac;
	}

	public Document loadSubmittedData(String formId, String submittedDataFilename) throws Exception {
		IWSlideService service = getIWSlideService();

		if (submittedDataFilename == null || formId == null)
			throw new NullPointerException("submitted_data_id or formId is not provided");

		String resourcePath = getSubmittedDataResourcePath(formId, submittedDataFilename);
		InputStream is = service.getInputStream(resourcePath);

		DocumentBuilder docBuilder = XmlUtil.getDocumentBuilder();
		Document submitted_data = docBuilder.parse(is);

		return submitted_data;
	}

	public List<SubmittedDataBean> listSubmittedData(String formId) throws Exception {
		throw new UnsupportedOperationException("Not supported yet, implement with new submission storage");

		/*if (formId == null)
			throw new NullPointerException("Form identifier is not set");

		WebdavResource form_folder = getWebdavExtendedResource(new StringBuilder(
		        SUBMITTED_DATA_PATH).append(CoreConstants.SLASH).append(formId)
		        .toString());

		if (form_folder == null)
			throw new NullPointerException(
			        "Error during form submissions folder retrieve");

		if (!form_folder.exists())
			return new ArrayList<SubmittedDataBean>();

		WebdavResources child_resources = form_folder.getChildResources();

		@SuppressWarnings("unchecked")
		Enumeration<WebdavResource> resources = child_resources.getResources();

		DocumentBuilder docBuilder = XmlUtil.getDocumentBuilder();

		List<SubmittedDataBean> submitted_data = new ArrayList<SubmittedDataBean>();

		while (resources.hasMoreElements()) {
			WebdavResource webdav_resource = resources.nextElement();

			final String displayName = webdav_resource.getDisplayName();
			if (displayName.startsWith("."))
				continue; // skip .DS_Store and other junk files

			try {

				InputStream is = webdav_resource.getMethodData();
				Document submitted_data_doc = docBuilder.parse(is);

				SubmittedDataBean data_bean = new SubmittedDataBean();
				data_bean.setSubmittedDataElement(submitted_data_doc
				        .getDocumentElement());
				data_bean.setId(displayName);

				submitted_data.add(data_bean);

			} catch (Exception e) {
				logger.log(Level.SEVERE,
				    "Error when retrieving/parsing submitted data file", e);
			}
		}
		return submitted_data;*/
	}

	@Override
	@Transactional(readOnly = false)
	public String saveSubmittedData(
			Long formId,
			InputStream is,
	        String representationIdentifier,
	        boolean finalSubmission,
	        Integer formSubmitter
	) throws IOException {
		if (formId == null || is == null)
			throw new IllegalArgumentException("Not enough arguments. FormId=" + formId + ", stream=" + is);

		if (StringUtil.isEmpty(representationIdentifier)) {
			representationIdentifier = String.valueOf(System.currentTimeMillis());
		}

		String path = storeSubmissionData(formId.toString(), representationIdentifier, is);

		XForm xform = getXformsDAO().find(XForm.class, formId);

		if (xform == null)
			throw new RuntimeException("No xform found for formId provided=" + formId);

		String submissionUUID = UUIDGenerator.getInstance().generateUUID();

		XFormSubmission xformSubmission = new XFormSubmission();
		try {
			xformSubmission.setDateSubmitted(new Date());
			xformSubmission.setSubmissionStorageIdentifier(path);
			xformSubmission.setSubmissionStorageType(slideStorageType);
			xformSubmission.setIsFinalSubmission(finalSubmission);
			xformSubmission.setSubmissionUUID(submissionUUID);
			xformSubmission.setXform(xform);
			xformSubmission.setFormSubmitter(formSubmitter);

			doEnsureSubmitterIsKnown(xformSubmission);

			getXformsDAO().persist(xformSubmission);

			return submissionUUID;
		} finally {
			ELUtil.getInstance().publishEvent(new FormSavedEvent(this, xformSubmission.getSubmissionId()));
		}
	}

	private User getSubmissionOwner(XFormSubmission submission) {
		Map<?, ?> beans = null;
		try {
			beans = WebApplicationContextUtils.getWebApplicationContext(IWMainApplication.getDefaultIWMainApplication().getServletContext())
					.getBeansOfType(FormAssetsResolver.class);
		} catch (Exception e) {}
		if (MapUtil.isEmpty(beans)) {
			String personalId = submission.getVariableValue(SubmissionDataBean.VARIABLE_OWNER_PERSONAL_ID);
			if (StringUtil.isEmpty(personalId))
				return null;

			try {
				UserBusiness userBusiness = IBOLookup.getServiceInstance(IWMainApplication.getDefaultIWApplicationContext(), UserBusiness.class);
				return userBusiness.getUser(personalId);
			} catch (Exception e) {
				getLogger().warning("Unable to resolve user by personal ID ('" + personalId + "') for submission " + submission);
			}

			return null;
		}

		for (Object bean: beans.values()) {
			if (bean instanceof FormAssetsResolver) {
				User owner = ((FormAssetsResolver) bean).getOwner(submission);
				if (owner != null) {
					return owner;
				}
			}
		}

		return null;
	}

	private void doEnsureSubmitterIsKnown(XFormSubmission submission) {
		if (submission.getFormSubmitter() != null) {
			return;
		}

		User submitter = getSubmissionOwner(submission);
		if (submitter == null) {
			return;
		}

		Integer submitterId = null;
		try {
			submitterId = Integer.valueOf(submitter.getId());
			submission.setFormSubmitter(submitterId);
			getLogger().info("Set submitter ID ('" + submitterId + "') for submission " + submission);
		} catch (Exception e) {
			getLogger().log(Level.WARNING, "Error setting submitter ID (user ID: '" + submitterId + "') for submission " + submission, e);
		}
	}

	private String storeSubmissionData(String formId, String identifier, InputStream is) throws IOException {
		// path equals SUBMITTED_DATA_PATH + formId + identifier,
		// so we get something similar like
		// /files/forms/submissions/123/P-xx/submission.xml,
		// and files at /files/forms/submissions/123/P-xx/uploads/file1.doc
		String path = new StringBuilder(SUBMITTED_DATA_PATH).append(CoreConstants.SLASH).append(formId).append(CoreConstants.SLASH).append(identifier)
			.append(CoreConstants.SLASH).toString();

		IWSlideService service = getIWSlideService();
		if (!service.uploadFile(path, submissionFileName, MimeTypeUtil.MIME_TYPE_XML, is)) {
			throw new RuntimeException("Error uploading data to repository: " + path + submissionFileName);
		}

		return path;
	}

	@Override
	@Transactional(readOnly = false)
	public void invalidateSubmittedDataByExistingSubmission(
	        String submissionUUID) {

		if (!StringUtil.isEmpty(submissionUUID)) {
			XFormSubmission xformSubmission = getXformsDAO()
			        .getSubmissionBySubmissionUUID(submissionUUID);
			xformSubmission.setIsValidSubmission(false);
		}
	}

	@Override
	@Transactional(readOnly = false)
	public String saveSubmittedDataByExistingSubmission(String submissionUUID, Long formId, InputStream is, String identifier, Integer formSubmitter) throws IOException {
		if (StringUtil.isEmpty(identifier))
			identifier = String.valueOf(System.currentTimeMillis());

		boolean isFinalSubmission = false;
		XFormSubmission xformSubmission = null;
		if (submissionUUID != null)
			xformSubmission = getXformsDAO().getSubmissionBySubmissionUUID(submissionUUID);

		if (xformSubmission == null) {
			submissionUUID = saveSubmittedData(formId, is, identifier, isFinalSubmission, formSubmitter);
		} else {
			if (submissionUUID.length() != 36) {
				submissionUUID = UUIDGenerator.getInstance().generateUUID();
				xformSubmission.setSubmissionUUID(submissionUUID);
			}

			String path = storeSubmissionData(formId.toString(), identifier, is);
			xformSubmission.setDateSubmitted(new Date());
			xformSubmission.setSubmissionStorageIdentifier(path);
			xformSubmission.setIsFinalSubmission(isFinalSubmission);
			if (xformSubmission.getFormSubmitter() != null && formSubmitter == null) {
				logger.info("Not setting null value for form submitter column! Form ID: " + formId + ", identifier: " + identifier + ", submission UUID: " + submissionUUID);
			} else {
				xformSubmission.setFormSubmitter(formSubmitter);
			}
			xformSubmission = getXformsDAO().merge(xformSubmission);
		}

		return submissionUUID;
	}

	protected String generateFormId(String name) {
		name = name.replaceAll("-", CoreConstants.UNDER);
		String result = name + CoreConstants.MINUS + new Date();
		String formId = result.replaceAll(" |:|\n", CoreConstants.UNDER)
		        .toLowerCase();

		char[] chars = formId.toCharArray();
		StringBuilder correctFormId = new StringBuilder(formId.length());

		for (int i = 0; i < chars.length; i++) {
			int charId = chars[i];

			if ((charId > 47 && charId < 58) || charId == 45 || charId == 95
			        || (charId > 96 && charId < 123))
				correctFormId.append(chars[i]);
		}

		return correctFormId.toString();
	}

	public void unlockForm(String form_id) {

		if (true)
			return;
		/*
		 * try { WebdavResource webdavResource = loadFormResource(FORMS_PATH,
		 * form_id, false);
		 *
		 * if(webdavResource == null || !webdavResource.isLocked()) return;
		 *
		 * webdavResource.unlockMethod(); } catch (IOException e) {
		 * logger.log(Level.SEVERE, "Error loading form from Webdav: " +
		 * form_id, e); } catch (FormLockException e) {
		 * logger.log(Level.WARNING,
		 * "FormLockException caught while loading form when lock is irrelevant for form id: "
		 * +form_id); }
		 */
	}

	public XFormsDAO getXformsDAO() {
		return xformsDAO;
	}

	@Autowired
	public void setXformsDAO(XFormsDAO xformsDAO) {
		this.xformsDAO = xformsDAO;
	}

	public DocumentManagerFactory getDocumentManagerFactory() {
		return documentManagerFactory;
	}

	@Autowired
	public void setDocumentManagerFactory(
	        DocumentManagerFactory documentManagerFactory) {
		this.documentManagerFactory = documentManagerFactory;
	}

}