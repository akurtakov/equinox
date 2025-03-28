/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.device;

import org.osgi.framework.ServiceReference;

/**
 * Match implementation class.
 */
public class Match implements org.osgi.service.device.Match {

	private final ServiceReference driver;
	private final int matchValue;

	Match(ServiceReference driver, int matchValue) {
		this.driver = driver;
		this.matchValue = matchValue;
	}

	@Override
	public ServiceReference getDriver() {
		return driver;
	}

	@Override
	public int getMatchValue() {
		return matchValue;
	}
}
