package com.idega.block.form.process;

import com.idega.jbpm.def.impl.DefaultViewImpl;

/**
 * @author <a href="mailto:civilis@idega.com">Vytautas Čivilis</a>
 * @version $Revision: 1.1 $
 *
 * Last modified: $Date: 2007/10/14 10:51:07 $ by $Author: civilis $
 */
public class XFormsView extends DefaultViewImpl {

	public static final String VIEW_TYPE = "xforms";
	
	public XFormsView() {
		setViewType(VIEW_TYPE);
	}
}