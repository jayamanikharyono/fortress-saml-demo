/*
 *   Licensed to the Apache Software Foundation (ASF) under one
 *   or more contributor license agreements.  See the NOTICE file
 *   distributed with this work for additional information
 *   regarding copyright ownership.  The ASF licenses this file
 *   to you under the Apache License, Version 2.0 (the
 *   "License"); you may not use this file except in compliance
 *   with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an
 *   "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *   KIND, either express or implied.  See the License for the
 *   specific language governing permissions and limitations
 *   under the License.
 *
 */
package org.samlsample.control;

import org.apache.commons.collections.CollectionUtils;
import org.apache.directory.fortress.core.*;
import org.apache.directory.fortress.core.SecurityException;
import org.apache.directory.fortress.core.util.Config;
import org.apache.directory.fortress.realm.*;
import org.apache.log4j.Logger;
import org.apache.wicket.Component;
import org.apache.directory.fortress.core.model.Permission;
import org.apache.directory.fortress.core.model.Session;
import org.apache.directory.fortress.core.model.User;

import javax.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;

/**
 * Common static utils and wrappers used by Wicket web apps to make fortress style security calls.
 *
 * @author <a href="mailto:dev@directory.apache.org">Apache Directory Project</a>
 * @version $Rev$
 */
public class SecUtils
{
    private static final Logger LOG = Logger.getLogger( SecUtils.class.getName() );
    private static final String PERMS_CACHED = "perms.cached";
    public static final boolean IS_PERM_CACHED = ( ( Config.getProperty( PERMS_CACHED ) != null ) && ( Config
        .getProperty( PERMS_CACHED ).equalsIgnoreCase( "true" ) ) );

    public static String FORTRESS_SAML_DEMO_LOGOUT_URL = "/fortress-saml-demo/saml/logout?local=true";

    /**
     * Return the fortress session that is cached within the wicket session object.
     *
     * @param component needed to get handle to wicket session.
     * @return fortress session object.
     */
    public static Session getSession(Component component)
    {
        return ( ( FtSession ) component.getSession() ).getSession();
    }

    /**
     * Enables fortress session on behalf of a java.security.Principal retrieved from the container.
     *
     * @param component
     * @param servletReq
     * @param j2eePolicyMgr
     * @param accessMgr
     * @throws SecurityException
     */
    public static void enableFortress( Component component, HttpServletRequest servletReq, J2eePolicyMgr j2eePolicyMgr, AccessMgr accessMgr ) throws SecurityException
    {
        // Get the principal from the container:
        Principal principal = servletReq.getUserPrincipal();
        // Is this a secured page && has the User successfully authenticated already?
        boolean isSecured = principal != null;
        if(isSecured)
        {
            // Only perform this step once per user web session:
            if( !isLoggedIn( component ) )
            {
                String userId = principal.getName();
                // Create the fortress session and assert into the Web app's session along with user's perms:
                SecUtils.initializeFtSession( component, j2eePolicyMgr, accessMgr, userId );
            }
        }
        else
        {
            LOG.warn( "Unsecured request: " + servletReq.getRequestURL() );
            throw new RuntimeException( "Unauthenticated user detected for request:" + servletReq.getRequestURL() );
        }
    }

    /**
     * Returns the fortress arbac perms that are cashed in the wicket session.
     *
     * @param component needed to get a handle on the wicket session object.
     * @return collection of fortress admin perms.
     */
    static List<Permission> getPermissions(Component component)
    {
        return ( ( FtSession ) component.getSession() ).getPermissions();
    }

    /**
     * Retrieve RBAC session permissions from Fortress and place in the Wicket session.
     */
    static void getPermissions( Component component, AccessMgr accessMgr )
    {
        try
        {
            if ( IS_PERM_CACHED )
            {
                FtSession session = ( FtSession ) component.getSession();
                List<Permission> permissions = accessMgr.sessionPermissions( session.getSession() );
                ( ( FtSession ) FtSession.get() ).setPermissions( permissions );
            }
        }
        catch ( org.apache.directory.fortress.core.SecurityException se )
        {
            String error = "getPermissions caught SecurityException=" + se;
            LOG.error( error );
            throw new RuntimeException( error );
        }
    }

    /**
     * Is the supplied permission in the wicket session cache?  Called by buttons.
     * if not found, button will be invisible.
     *
     * @param permission fortress perm requires {link @Permission#objName} and {link @Permission#opName} are set.
     * @param component needed to get handle on the wicket session object.
     * @return true if found, false otherwise
     */
    static boolean isFound( Permission permission, Component component )
    {
        List<Permission> permissions = SecUtils.getPermissions( component );
        return CollectionUtils.isNotEmpty( permissions ) && permissions.contains( permission );
    }

    /**
     * Create a Fortress Session and load into a Wicket Session along with perms.
     *
     * @param component contains handle to wicket session.
     * @param j2eePolicyMgr used to call deserize api
     * @param accessMgr used to call fortress api for role op
     * @param userId contains the instance of fortress session deserialized.
     */
    static void initializeFtSession(Component component, J2eePolicyMgr j2eePolicyMgr, AccessMgr accessMgr, String
        userId) throws SecurityException
    {
        Session realmSession = null;
        try
        {
            realmSession = j2eePolicyMgr.createSession( new User( userId ), true );
        }
        catch( SecurityException se )
        {
            throw new RuntimeException( se );
        }
        if(realmSession != null)
        {
            synchronized ( ( FtSession ) FtSession.get() )
            {
                if ( SecUtils.getSession( component ) == null )
                {
                    LOG.info( "realmSession user: " + realmSession.getUserId() );
                    // Retrieve user permissions and attach RBAC session to Wicket session:
                    ( ( FtSession ) FtSession.get() ).setSession( realmSession );
                    getPermissions( component, accessMgr );
                }
            }
        }
    }

    /**
     * If user has a wicket session then considered logged in.
     *
     * @return true if wicket session is not null
     */
    static boolean isLoggedIn( Component component )
    {
        boolean isLoggedIn = false;
        if ( getSession( component ) != null )
        {
            isLoggedIn = true;
        }
        return isLoggedIn;
    }

    /**
     * parse a string value: objName.opName
     *
     * @param id '.' separated value
     * @return {@link org.apache.directory.fortress.core.model.Permission}
     */
    static Permission getPermFromId( String id )
    {
        Permission perm = null;
        String[] parts = id.split( "\\." );
        if( parts.length > 1)
        {
            String objName = parts[0];
            String opName = parts[1];
            perm = new Permission( objName, opName );
        }
        return perm;
    }
}