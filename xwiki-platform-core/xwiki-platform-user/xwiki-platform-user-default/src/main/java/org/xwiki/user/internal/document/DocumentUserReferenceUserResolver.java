/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.user.internal.document;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.user.CurrentUserReference;
import org.xwiki.user.GuestUserReference;
import org.xwiki.user.SuperAdminUserReference;
import org.xwiki.user.internal.GuestUser;
import org.xwiki.user.internal.SuperAdminUser;
import org.xwiki.user.User;
import org.xwiki.user.UserReference;
import org.xwiki.user.UserResolver;

/**
 * Converts a {@link UserReference} into a {@link User}.
 *
 * @version $Id$
 * @since 12.2RC1
 */
@Component
@Named("org.xwiki.user.internal.document.DocumentUserReference")
@Singleton
public class DocumentUserReferenceUserResolver extends AbstractDocumentUserResolver<UserReference>
{
    @Inject
    @Named("org.xwiki.user.CurrentUserReference")
    private UserResolver<UserReference> currentUserResolver;

    @Override
    public User resolve(UserReference userReference, Object... parameters)
    {
        User user;
        if (userReference == null | CurrentUserReference.INSTANCE == userReference) {
            user = this.currentUserResolver.resolve(null);
        } else if (GuestUserReference.INSTANCE == userReference) {
            user = new GuestUser(this.guestConfigurationSource);
        } else if (SuperAdminUserReference.INSTANCE == userReference) {
            user = new SuperAdminUser(this.superAdminConfigurationSource);
        } else {
            user = resolveUser((DocumentUserReference) userReference);
        }
        return user;
    }
}