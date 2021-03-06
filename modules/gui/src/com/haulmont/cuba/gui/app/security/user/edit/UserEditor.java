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
package com.haulmont.cuba.gui.app.security.user.edit;

import com.haulmont.chile.core.model.MetaClass;
import com.haulmont.cuba.client.ClientConfig;
import com.haulmont.cuba.core.entity.Entity;
import com.haulmont.cuba.core.global.*;
import com.haulmont.cuba.gui.WindowManager.OpenType;
import com.haulmont.cuba.gui.WindowParam;
import com.haulmont.cuba.gui.WindowParams;
import com.haulmont.cuba.gui.app.security.user.NameBuilderListener;
import com.haulmont.cuba.gui.components.*;
import com.haulmont.cuba.gui.components.actions.ItemTrackingAction;
import com.haulmont.cuba.gui.components.actions.RemoveAction;
import com.haulmont.cuba.gui.data.CollectionDatasource;
import com.haulmont.cuba.gui.data.DataSupplier;
import com.haulmont.cuba.gui.data.Datasource;
import com.haulmont.cuba.gui.data.DsContext;
import com.haulmont.cuba.gui.data.impl.AbstractDatasource;
import com.haulmont.cuba.gui.data.impl.DatasourceImplementation;
import com.haulmont.cuba.gui.theme.ThemeConstants;
import com.haulmont.cuba.gui.xml.layout.ComponentsFactory;
import com.haulmont.cuba.security.entity.*;
import com.haulmont.cuba.security.global.UserSession;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;

import javax.inject.Inject;
import java.util.*;

public class UserEditor extends AbstractEditor<User> {

    @Inject
    protected DsContext dsContext;

    @Inject
    protected DataSupplier dataSupplier;

    @Inject
    protected Datasource<User> userDs;

    @Inject
    protected CollectionDatasource<UserRole, UUID> rolesDs;

    @Inject
    protected CollectionDatasource<UserSubstitution, UUID> substitutionsDs;

    @Inject
    protected Table rolesTable;

    @Inject
    protected Table substTable;

    @Inject
    protected FieldGroup fieldGroupLeft;

    @Inject
    protected FieldGroup fieldGroupRight;

    protected PasswordField passwField;
    protected PasswordField confirmPasswField;
    protected LookupField languageLookup;
    protected LookupField timeZoneLookup;

    @Inject
    protected UserSession userSession;

    @Inject
    protected ComponentsFactory factory;

    @Inject
    protected Configuration configuration;

    @Inject
    protected Metadata metadata;

    @Inject
    protected Security security;

    @Inject
    protected PasswordEncryption passwordEncryption;

    @Inject
    protected ThemeConstants themeConstants;

    @Inject
    protected TimeZones timeZones;

    @WindowParam(name = "initCopy")
    protected Boolean initCopy;

    public interface Companion {
        void initPasswordField(PasswordField passwordField);
        void refreshUserSubstitutions();
    }

    @Override
    public void init(Map<String, Object> params) {
        userDs.addItemPropertyChangeListener(new NameBuilderListener<>(userDs));
        userDs.addItemPropertyChangeListener(e -> {
            if (e.getProperty().equals("timeZoneAuto")) {
                timeZoneLookup.setEnabled(!Boolean.TRUE.equals(e.getValue()));
            }
        });

        rolesTable.addAction(new AddRoleAction());
        rolesTable.addAction(new EditRoleAction());
        rolesTable.addAction(new RemoveRoleAction(rolesTable, false));

        substTable.addAction(new AddSubstitutedAction());
        substTable.addAction(new EditSubstitutedAction());
        substTable.addAction(new RemoveAction(substTable, false));

        initCustomFields(PersistenceHelper.isNew(WindowParams.ITEM.getEntity(params)));

        dsContext.addAfterCommitListener((context, result) -> {
            for (Entity entity : result) {
                if (entity.equals(userSession.getUser())) {
                    userSession.setUser((User) entity);
                }
                if (entity.equals(userSession.getSubstitutedUser())) {
                    userSession.setSubstitutedUser((User) entity);
                }
            }

            Companion companion = getCompanion();
            if (companion != null && userSession.getUser().equals(getItem())) {
                for (Entity entity : result) {
                    if (entity instanceof UserSubstitution) {
                        companion.refreshUserSubstitutions();
                        break;
                    }
                }
            }
        });
    }

    @Override
    protected void postInit() {
        setCaption(PersistenceHelper.isNew(getItem()) ?
                getMessage("createCaption") : formatMessage("editCaption", getItem().getLogin()));

        timeZoneLookup.setEnabled(!Boolean.TRUE.equals(getItem().getTimeZoneAuto()));

        // Do not show roles which are not allowed by security constraints
        LoadContext lc = new LoadContext(Role.class);
        lc.setQueryString("select r from sec$Role r");
        lc.setView(View.MINIMAL);
        List<Role> allowedRoles = dataSupplier.loadList(lc);

        Collection<UserRole> items = rolesDs.getItems();
        for (UserRole userRole : items) {
            if (!allowedRoles.contains(userRole.getRole())) {
                rolesDs.excludeItem(userRole);
            }

        }

        if (BooleanUtils.isTrue(initCopy)) {
            initCopy();
        }

        // if we add default roles, rolesDs becomes modified on setItem
        ((AbstractDatasource) rolesDs).setModified(false);
    }

    @Override
    protected void initNewItem(User item) {
        addDefaultRoles(item);
        item.setLanguage(messages.getTools().localeToString(userSession.getLocale()));
        initUserGroup(item);
    }

    protected void initUserGroup(User user) {
        LoadContext ctx = new LoadContext(Group.class);
        ctx.setQueryString("select g from sec$Group g");
        ctx.setView(View.MINIMAL);
        List<Group> groups = dataSupplier.loadList(ctx);
        if (groups.size() == 1) {
            user.setGroup(groups.get(0));
        }
    }

    private void addDefaultRoles(User user) {
        LoadContext ctx = new LoadContext(Role.class);
        ctx.setQueryString("select r from sec$Role r where r.defaultRole = true");
        List<Role> defaultRoles = dataSupplier.loadList(ctx);

        List<UserRole> newRoles = new ArrayList<>();
        if (user.getUserRoles() != null) {
            newRoles.addAll(user.getUserRoles());
        }

        final MetaClass metaClass = rolesDs.getMetaClass();
        for (Role role : defaultRoles) {
            UserRole userRole = dataSupplier.newInstance(metaClass);
            userRole.setRole(role);
            userRole.setUser(user);
            newRoles.add(userRole);
        }

        user.setUserRoles(newRoles);
    }

    private void initCustomFields(final boolean isNew) {
        fieldGroupLeft.addCustomField("passw", new FieldGroup.CustomFieldGenerator() {
            @Override
            public Component generateField(Datasource datasource, String propertyId) {
                passwField = factory.createComponent(PasswordField.class);
                if (isNew) {
                    passwField.setRequiredMessage(getMessage("passwMsg"));

                    Companion companion = getCompanion();
                    if (companion != null) {
                        companion.initPasswordField(passwField);
                    } else {
                        passwField.setRequired(true);
                    }
                    passwField.addValueChangeListener(e -> ((DatasourceImplementation) userDs).setModified(true));
                } else {
                    passwField.setVisible(false);
                }
                return passwField;
            }
        });

        fieldGroupLeft.addCustomField("confirmPassw", new FieldGroup.CustomFieldGenerator() {
            @Override
            public Component generateField(Datasource datasource, String propertyId) {
                confirmPasswField = factory.createComponent(PasswordField.class);
                if (isNew) {
                    confirmPasswField.setRequiredMessage(getMessage("confirmPasswMsg"));

                    Companion companion = getCompanion();
                    if (companion != null) {
                        companion.initPasswordField(confirmPasswField);
                    } else {
                        confirmPasswField.setRequired(true);
                    }
                    confirmPasswField.addValueChangeListener(e -> ((DatasourceImplementation) userDs).setModified(true));
                } else {
                    confirmPasswField.setVisible(false);
                }
                return confirmPasswField;
            }
        });

        fieldGroupRight.addCustomField("language", new FieldGroup.CustomFieldGenerator() {
            @Override
            public Component generateField(Datasource datasource, String propertyId) {
                languageLookup = factory.createComponent(LookupField.class);

                languageLookup.setDatasource(datasource, propertyId);
                languageLookup.setRequired(false);

                Map<String, Locale> locales = configuration.getConfig(GlobalConfig.class).getAvailableLocales();
                TreeMap<String, Object> options = new TreeMap<>();
                for (Map.Entry<String, Locale> entry : locales.entrySet()) {
                    options.put(entry.getKey(), messages.getTools().localeToString(entry.getValue()));
                }
                languageLookup.setOptionsMap(options);
                return languageLookup;
            }
        });

        fieldGroupRight.addCustomField("timeZone", new FieldGroup.CustomFieldGenerator() {
            @Override
            public Component generateField(Datasource datasource, String propertyId) {
                HBoxLayout hbox = factory.createComponent(HBoxLayout.class);
                hbox.setSpacing(true);

                timeZoneLookup = factory.createComponent(LookupField.class);

                timeZoneLookup.setDatasource(datasource, propertyId);
                timeZoneLookup.setRequired(false);

                MetaClass userMetaClass = userDs.getMetaClass();
                timeZoneLookup.setEditable(fieldGroupRight.isEditable()
                        && security.isEntityAttrUpdatePermitted(userMetaClass, propertyId));

                Map<String, Object> options = new TreeMap<>();
                for (String id : TimeZone.getAvailableIDs()) {
                    TimeZone timeZone = TimeZone.getTimeZone(id);
                    options.put(timeZones.getDisplayNameLong(timeZone), id);
                }
                timeZoneLookup.setOptionsMap(options);

                hbox.add(timeZoneLookup);

                CheckBox autoDetectField = factory.createComponent(CheckBox.class);
                autoDetectField.setDatasource(datasource, "timeZoneAuto");
                autoDetectField.setCaption(messages.getMainMessage("timeZone.auto"));
                autoDetectField.setDescription(messages.getMainMessage("timeZone.auto.descr"));
                autoDetectField.setAlignment(Alignment.MIDDLE_RIGHT);

                autoDetectField.setEditable(fieldGroupRight.isEditable()
                        && security.isEntityAttrUpdatePermitted(userMetaClass, "timeZoneAuto"));

                hbox.add(autoDetectField);

                hbox.expand(timeZoneLookup);

                return hbox;
            }
        });

        fieldGroupRight.addCustomField("group", new FieldGroup.CustomFieldGenerator() {
            @Override
            public Component generateField(Datasource datasource, String propertyId) {
                PickerField pickerField = factory.createComponent(PickerField.class);
                pickerField.setDatasource(datasource, propertyId);
                pickerField.setRequired(true);
                pickerField.setRequiredMessage(getMessage("groupMsg"));

                PickerField.LookupAction action = pickerField.addLookupAction();
                action.setLookupScreenOpenType(OpenType.DIALOG);

                return pickerField;
            }
        });
    }

    @Override
    protected boolean preCommit() {
        if (rolesDs.isModified()) {
            @SuppressWarnings("unchecked")
            DatasourceImplementation<UserRole> rolesDsImpl = (DatasourceImplementation) rolesDs;

            CommitContext ctx = new CommitContext(Collections.emptyList(), rolesDsImpl.getItemsToDelete());
            dataSupplier.commit(ctx);

            List<UserRole> modifiedRoles = new ArrayList<>(rolesDsImpl.getItemsToCreate());
            modifiedRoles.addAll(rolesDsImpl.getItemsToUpdate());
            rolesDsImpl.committed(Collections.emptySet());
            for (UserRole userRole : modifiedRoles) {
                rolesDsImpl.modified(userRole);
            }
        }

        User user = getItem();

        if (PersistenceHelper.isNew(user)) {
            String password = passwField.getValue();
            String passwordConfirmation = confirmPasswField.getValue();

            if (passwField.isRequired() && (StringUtils.isBlank(password) || StringUtils.isBlank(passwordConfirmation))) {
                showNotification(getMessage("emptyPassword"), NotificationType.WARNING);
                return false;
            } else {
                if (StringUtils.equals(password, passwordConfirmation)) {
                    if (StringUtils.isNotEmpty(password)) {
                        ClientConfig passwordPolicyConfig = configuration.getConfig(ClientConfig.class);
                        if (passwordPolicyConfig.getPasswordPolicyEnabled()) {
                            String regExp = passwordPolicyConfig.getPasswordPolicyRegExp();
                            if (!password.matches(regExp)) {
                                showNotification(getMessage("simplePassword"), NotificationType.WARNING);
                                return false;
                            }
                        }

                        String passwordHash = passwordEncryption.getPasswordHash(user.getId(), password);
                        user.setPassword(passwordHash);
                    }

                    return true;
                } else {
                    showNotification(getMessage("passwordsDoNotMatch"), NotificationType.WARNING);
                    return false;
                }
            }
        } else {
            return true;
        }
    }

    public void initCopy() {
        @SuppressWarnings("unchecked")
        DatasourceImplementation<UserRole> rolesDsImpl = (DatasourceImplementation) rolesDs;
        for (UserRole item : rolesDs.getItems()) {
            rolesDsImpl.modified(item);
        }
    }

    protected class AddRoleAction extends AbstractAction {

        public AddRoleAction() {
            super("add");

            icon = themeConstants.get("actions.Add.icon");

            ClientConfig clientConfig = configuration.getConfig(ClientConfig.class);
            setShortcut(clientConfig.getTableAddShortcut());
        }

        @Override
        public void actionPerform(Component component) {
            Map<String, Object> lookupParams = Collections.singletonMap("windowOpener", "sec$User.edit");
            Lookup roleLookupWindow = openLookup("sec$Role.lookup", new Lookup.Handler() {
                @Override
                public void handleLookup(Collection items) {
                    Collection<String> existingRoleNames = getExistingRoleNames();
                    rolesDs.suspendListeners();
                    try {
                        for (Object item : items) {
                            Role role = (Role) item;
                            if (existingRoleNames.contains(role.getName())) continue;

                            final MetaClass metaClass = rolesDs.getMetaClass();
                            UserRole userRole = dataSupplier.newInstance(metaClass);
                            userRole.setRole(role);
                            userRole.setUser(userDs.getItem());

                            rolesDs.addItem(userRole);
                            existingRoleNames.add(role.getName());
                        }
                    } finally {
                        rolesDs.resumeListeners();
                    }
                }

                private Collection<String> getExistingRoleNames() {
                    User user = userDs.getItem();
                    Collection<String> existingRoleNames = new HashSet<>();
                    if (user.getUserRoles() != null) {
                        for (UserRole userRole : user.getUserRoles()) {
                            if (userRole.getRole() != null)
                                existingRoleNames.add(userRole.getRole().getName());
                        }
                    }
                    return existingRoleNames;
                }

            }, OpenType.THIS_TAB, lookupParams);

            roleLookupWindow.addCloseListener(actionId -> {
                rolesTable.requestFocus();
            });

            Component lookupComponent = roleLookupWindow.getLookupComponent();
            if (lookupComponent instanceof Table) {
                ((Table) lookupComponent).setMultiSelect(true);
            }
        }

        @Override
        public boolean isEnabled() {
            return super.isEnabled() &&
                    security.isEntityOpPermitted(
                            metadata.getSession().getClass(UserRole.class), EntityOp.CREATE);
        }

        @Override
        public String getCaption() {
            return getMessage("actions.Add");
        }
    }

    protected class EditRoleAction extends ItemTrackingAction {

        public EditRoleAction() {
            super("edit");

            icon = themeConstants.get("actions.Edit.icon");
        }

        @Override
        public void actionPerform(Component component) {
            if (rolesDs.getItem() == null)
                return;
            Window window = openEditor("sec$Role.edit", rolesDs.getItem().getRole(), OpenType.THIS_TAB);
            window.addCloseListener(actionId -> {
                if (Window.COMMIT_ACTION_ID.equals(actionId)) {
                    rolesDs.refresh();
                }
                rolesTable.requestFocus();
            });
        }

        @Override
        public String getCaption() {
            return getMessage("actions.Edit");
        }
    }

    protected class RemoveRoleAction extends RemoveAction {

        private boolean hasDefaultRole = false;

        public RemoveRoleAction(ListComponent owner, boolean autocommit) {
            super(owner, autocommit);
        }

        @Override
        protected void confirmAndRemove(Set selected) {
            hasDefaultRole = hasDefaultRole(selected);
            super.confirmAndRemove(selected);
        }

        @Override
        public String getConfirmationMessage() {
            if (hasDefaultRole)
                return getMessage("dialogs.Confirmation.RemoveDefaultRole");
            else
                return super.getConfirmationMessage();
        }

        private boolean hasDefaultRole(Set selected) {
            for (Object roleObj : selected) {
                UserRole role = (UserRole) roleObj;
                if (Boolean.TRUE.equals(role.getRole().getDefaultRole()))
                    return true;
            }
            return false;
        }

        @Override
        public boolean isEnabled() {
            return super.isEnabled() &&
                    security.isEntityOpPermitted(
                            metadata.getSession().getClass(UserRole.class), EntityOp.DELETE);
        }
    }

    protected class AddSubstitutedAction extends AbstractAction {

        public AddSubstitutedAction() {
            super("add");

            icon = themeConstants.get("actions.Add.icon");

            ClientConfig clientConfig = configuration.getConfig(ClientConfig.class);
            setShortcut(clientConfig.getTableAddShortcut());
        }

        @Override
        public void actionPerform(Component component) {
            final UserSubstitution substitution = metadata.create(UserSubstitution.class);
            substitution.setUser(userDs.getItem());

            int dialogWidth = themeConstants.getInt("cuba.gui.UserEditor.substitutionEditor.width");
            Window substitutionEditor = openEditor("sec$UserSubstitution.edit", substitution,
                    OpenType.DIALOG.width(dialogWidth), substitutionsDs);
            substitutionEditor.addCloseListener(actionId -> {
                substTable.requestFocus();
            });
        }

        @Override
        public boolean isEnabled() {
            return super.isEnabled() &&
                    security.isEntityOpPermitted(
                            metadata.getSession().getClass(UserSubstitution.class), EntityOp.CREATE);
        }
    }

    protected class EditSubstitutedAction extends ItemTrackingAction {

        public EditSubstitutedAction() {
            super("edit");

            icon = themeConstants.get("actions.Edit.icon");
        }

        @Override
        public void actionPerform(Component component) {
            getDialogOptions().setWidth(themeConstants.getInt("cuba.gui.UserEditor.substitutionEditor.width"));

            if (substitutionsDs.getItem() != null) {
                Window substitutionEditor = openEditor("sec$UserSubstitution.edit", substitutionsDs.getItem(),
                        OpenType.DIALOG, substitutionsDs);
                substitutionEditor.addCloseListener(actionId -> {
                    substTable.requestFocus();
                });
            }
        }
    }
}