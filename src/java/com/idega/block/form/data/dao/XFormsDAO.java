package com.idega.block.form.data.dao;

import java.util.List;

import com.idega.block.form.data.XForm;
import com.idega.core.persistence.GenericDao;
import com.idega.documentmanager.business.XFormState;

/**
 * @author <a href="mailto:civilis@idega.com">Vytautas Čivilis</a>
 * @version $Revision: 1.2 $
 *
 * Last modified: $Date: 2008/07/11 14:15:02 $ by $Author: anton $
 */
public interface XFormsDAO extends GenericDao {

	public abstract List<XForm> getAllXFormsByTypeAndStorageType(String formType, String formStorageType);
	
	public abstract XForm getXFormByParentVersion(Long parentFormId, Integer version, XFormState state);
	
	public abstract XForm getXFormById(Long formId);
}