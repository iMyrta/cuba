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

package com.haulmont.cuba.core.sys;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.text.StrLookup;
import org.apache.commons.lang.text.StrSubstitutor;
import org.apache.commons.lang.text.StrTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.ResourceUtils;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Properties;

/**
 * Base class for {@link AppContext} loaders of web applications.
 *
 */
public abstract class AbstractWebAppContextLoader extends AbstractAppContextLoader implements ServletContextListener {

    public static final String APP_PROPS_CONFIG_PARAM = "appPropertiesConfig";

    public static final String APP_PROPS_PARAM = "appProperties";

    private Logger log = LoggerFactory.getLogger(getClass());

    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent) {
        try {
            ServletContext sc = servletContextEvent.getServletContext();
            ServletContextHolder.setServletContext(sc);

            initAppProperties(sc);
            afterInitAppProperties();

            beforeInitAppContext();
            initAppContext();
            afterInitAppContext();

            AppContext.startContext();
            log.info("AppContext initialized");
        } catch (Throwable e) {
            log.error("Error initializing application", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent servletContextEvent) {
        AppContext.stopContext();
        AppContext.setApplicationContext(null);
    }

    protected void initAppProperties(ServletContext sc) {
        // get properties from web.xml
        String appProperties = sc.getInitParameter(APP_PROPS_PARAM);
        if (appProperties != null) {
            StrTokenizer tokenizer = new StrTokenizer(appProperties);
            for (String str : tokenizer.getTokenArray()) {
                int i = str.indexOf("=");
                if (i < 0)
                    continue;
                String name = StringUtils.substring(str, 0, i);
                String value = StringUtils.substring(str, i+1);
                if (!StringUtils.isBlank(name)) {
                    AppContext.setProperty(name, value);
                }
            }
        }

        // get properties from a set of app.properties files defined in web.xml
        String propsConfigName = getAppPropertiesConfig(sc);
        if (propsConfigName == null)
            throw new IllegalStateException(APP_PROPS_CONFIG_PARAM + " servlet context parameter not defined");

        final Properties properties = new Properties();

        DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
        StrTokenizer tokenizer = new StrTokenizer(propsConfigName);
        tokenizer.setQuoteChar('"');
        for (String str : tokenizer.getTokenArray()) {
            log.trace("Processing properties location: " + str);
            InputStream stream = null;
            try {
                if (ResourceUtils.isUrl(str) || str.startsWith(ResourceLoader.CLASSPATH_URL_PREFIX)) {
                    Resource resource = resourceLoader.getResource(str);
                    if (resource.exists())
                        stream = resource.getInputStream();
                } else {
                   stream = sc.getResourceAsStream(str);
                }

                if (stream != null) {
                    log.trace("Loading app properties from " + str);
                    try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8.name())) {
                        properties.load(reader);
                    }
                } else
                    log.trace("Resource " + str + " not found, ignore it");

            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                IOUtils.closeQuietly(stream);
            }
        }

        // interpolation
        StrSubstitutor substitutor = new StrSubstitutor(new StrLookup() {
            @Override
            public String lookup(String key) {
                String subst = properties.getProperty(key);
                return subst != null ? subst : System.getProperty(key);
            }
        });
        for (Object key : properties.keySet()) {
            String value = substitutor.replace(properties.getProperty((String) key));
            AppContext.setProperty((String) key, value.trim());
        }
    }

    protected String getAppPropertiesConfig(ServletContext sc) {
        return sc.getInitParameter(APP_PROPS_CONFIG_PARAM);
    }

    @Override
    protected void afterInitAppProperties() {
        super.afterInitAppProperties();

        File file = new File(AppContext.getProperty("cuba.confDir"));
        if (!file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.mkdirs();
        }
        file = new File(AppContext.getProperty("cuba.tempDir"));
        if (!file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.mkdirs();
        }
    }

    public static class CubaServletConfig implements ServletConfig {
        protected String name;
        protected ServletContext servletContext;

        public CubaServletConfig(String name, ServletContext servletContext) {
            this.name = name;
            this.servletContext = servletContext;
        }

        @Override
        public String getServletName() {
            return name;
        }

        @Override
        public ServletContext getServletContext() {
            return servletContext;
        }

        @Override
        public String getInitParameter(String name) {
            return servletContext.getInitParameter(name);
        }

        @Override
        public Enumeration<String> getInitParameterNames() {
            return servletContext.getInitParameterNames();
        }
    }
}