/*
 * Copyright (c) 2008-2016 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.haulmont.cuba.web.gui.components;

import com.haulmont.chile.core.datatypes.Datatype;
import com.haulmont.chile.core.datatypes.Datatypes;
import com.haulmont.cuba.client.ClientConfig;
import com.haulmont.cuba.core.global.AppBeans;
import com.haulmont.cuba.core.global.Configuration;
import com.haulmont.cuba.gui.components.UploadComponentSupport;

import java.util.Set;

public abstract class WebAbstractUploadComponent<T extends com.vaadin.ui.Component>
        extends WebAbstractComponent<T>
        implements UploadComponentSupport {

    protected static final int BYTES_IN_MEGABYTE = 1048576;

    protected long fileSizeLimit = 0;

    protected Set<String> permittedExtensions;

    @Override
    public long getFileSizeLimit() {
        return fileSizeLimit;
    }

    protected long getActualFileSizeLimit() {
        final long maxSize;
        if (fileSizeLimit > 0) {
            maxSize = fileSizeLimit;
        } else {
            Configuration configuration = AppBeans.get(Configuration.NAME);
            final long maxUploadSizeMb = configuration.getConfig(ClientConfig.class).getMaxUploadSizeMb();
            maxSize = maxUploadSizeMb * BYTES_IN_MEGABYTE;
        }
        return maxSize;
    }

    @Override
    public Set<String> getPermittedExtensions() {
        return permittedExtensions;
    }

    @Override
    public void setPermittedExtensions(Set<String> permittedExtensions) {
        this.permittedExtensions = permittedExtensions;
    }

    protected String getFileSizeLimitString() {
        String fileSizeLimitString;
        if (fileSizeLimit > 0) {
            if (fileSizeLimit % BYTES_IN_MEGABYTE == 0) {
                fileSizeLimitString = String.valueOf(fileSizeLimit / BYTES_IN_MEGABYTE);
            } else {
                Datatype<Double> doubleDatatype = Datatypes.getNN(Double.class);
                double fileSizeInMb = fileSizeLimit / ((double) BYTES_IN_MEGABYTE);
                fileSizeLimitString = doubleDatatype.format(fileSizeInMb);
            }
        } else {
            Configuration configuration = AppBeans.get(Configuration.NAME);
            fileSizeLimitString = String.valueOf(configuration.getConfig(ClientConfig.class).getMaxUploadSizeMb());
        }
        return fileSizeLimitString;
    }
}