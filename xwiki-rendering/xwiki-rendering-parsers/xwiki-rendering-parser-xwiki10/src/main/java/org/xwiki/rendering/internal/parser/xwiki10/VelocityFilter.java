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
package org.xwiki.rendering.internal.parser.xwiki10;

import java.util.regex.Pattern;

import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.Requirement;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.phase.Initializable;
import org.xwiki.component.phase.InitializationException;
import org.xwiki.rendering.internal.parser.xwiki10.velocity.ExtendedVelocityParser;
import org.xwiki.rendering.internal.parser.xwiki10.velocity.ExtendedVelocityParserContext;
import org.xwiki.rendering.parser.xwiki10.AbstractFilter;
import org.xwiki.rendering.parser.xwiki10.FilterContext;
import org.xwiki.rendering.parser.xwiki10.util.CleanUtil;
import org.xwiki.velocity.internal.util.InvalidVelocityException;

/**
 * Register all Velocity comments in order to protect them from following filters. Protect velocity comments, convert
 * velocity macro into 2.0 macros/syntax and add needed 2.0 velocity macros and convert.
 * <p>
 * See http://velocity.apache.org/engine/releases/velocity-1.6.2/vtl-reference-guide.html
 * 
 * @version $Id$
 * @since 1.8M1
 */
@Component("velocity")
public class VelocityFilter extends AbstractFilter implements Initializable
{
    public static final String VELOCITY_SUFFIX = "velocity";

    public static final String VELOCITYOPEN_SUFFIX = VELOCITY_SUFFIX + "open";

    public static final String VELOCITYCLOSE_SUFFIX = VELOCITY_SUFFIX + "close";

    public static final String VELOCITYCOMMENT_SUFFIX =
        VELOCITY_SUFFIX + ExtendedVelocityParserContext.VelocityType.COMMENT;

    public static final String VELOCITY_SPATTERN =
        "(?:" + FilterContext.XWIKI1020TOKEN_OP + FilterContext.XWIKI1020TOKENIL + VELOCITY_SUFFIX + "\\p{L}*\\d+"
            + FilterContext.XWIKI1020TOKEN_CP + ")";

    public static final String VELOCITYOPEN_SPATTERN =
        "(?:" + FilterContext.XWIKI1020TOKEN_OP + FilterContext.XWIKI1020TOKENIL + VELOCITYOPEN_SUFFIX + "[\\d]+"
            + FilterContext.XWIKI1020TOKEN_CP + ")";

    public static final String VELOCITYCLOSE_SPATTERN =
        "(?:" + FilterContext.XWIKI1020TOKEN_OP + FilterContext.XWIKI1020TOKENIL + VELOCITYCLOSE_SUFFIX + "[\\d]+"
            + FilterContext.XWIKI1020TOKEN_CP + ")";

    public static final String VELOCITYCONTENT_SPATTERN =
        "(?:" + VELOCITYOPEN_SPATTERN + ".*" + VELOCITYCLOSE_SPATTERN + ")";

    public static final String VELOCITYCOMMENT_SPATTERN =
        "(?:" + FilterContext.XWIKI1020TOKEN_OP + FilterContext.XWIKI1020TOKENNI + VELOCITYCOMMENT_SUFFIX + "[\\d]+"
            + FilterContext.XWIKI1020TOKEN_CP + ")";

    public static final String NLGROUP_SPATTERN = "(?:(?:\n|" + VelocityFilter.VELOCITYCOMMENT_SPATTERN + ")*)";

    public static final String SPACEGROUP_SPATTERN = "(?:(?:[ \\t]|" + VelocityFilter.VELOCITYCOMMENT_SPATTERN + ")*)";

    public static final String EMPTY_OC_SPATTERN =
        VELOCITYOPEN_SPATTERN + "?" + VELOCITYCOMMENT_SPATTERN + "*" + VELOCITYCLOSE_SPATTERN + "?";

    public static final String SPACEGROUP_OC_SPATTERN =
        "[ \\t]*" + VELOCITYOPEN_SPATTERN + "?" + SPACEGROUP_SPATTERN + VELOCITYCLOSE_SPATTERN + "?" + "[ \\t]*";

    public static final Pattern VELOCITYOPEN_PATTERN = Pattern.compile(VELOCITYOPEN_SPATTERN);

    public static final Pattern VELOCITYCLOSE_PATTERN = Pattern.compile(VELOCITYCLOSE_SPATTERN);

    public static final Pattern VELOCITYCONTENT_PATTERN = Pattern.compile(VELOCITYCONTENT_SPATTERN, Pattern.DOTALL);

    /**
     * Used to lookup macros converters.
     */
    @Requirement
    private ComponentManager componentManager;

    private ExtendedVelocityParser velocityParser;

    /**
     * {@inheritDoc}
     * 
     * @see Initializable#initialize()
     */
    public void initialize() throws InitializationException
    {
        setPriority(20);

        this.velocityParser = new ExtendedVelocityParser(componentManager, getLogger());
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.parser.xwiki10.Filter#filter(java.lang.String,
     *      org.xwiki.rendering.parser.xwiki10.FilterContext)
     */
    public String filter(String content, FilterContext filterContext)
    {
        StringBuffer result = new StringBuffer();
        char[] array = content.toCharArray();

        ExtendedVelocityParserContext context = new ExtendedVelocityParserContext(filterContext);

        StringBuffer beforeVelocityBuffer = new StringBuffer();
        StringBuffer velocityBuffer = new StringBuffer();
        StringBuffer afterVelocityBuffer = new StringBuffer();

        boolean inVelocityMacro = false;
        int i = 0;

        for (; i < array.length;) {
            char c = array[i];

            context.setVelocity(false);
            context.setConversion(false);
            context.setInline(true);
            context.setProtectedBlock(true);
            context.setType(null);

            StringBuffer velocityBlock = new StringBuffer();

            try {
                if (c == '#') {
                    i = this.velocityParser.getKeyWord(array, i, velocityBlock, context);
                } else if (c == '$') {
                    i = this.velocityParser.getVar(array, i, velocityBlock, context);
                }
            } catch (InvalidVelocityException e) {
                getLogger().debug("Not a valid Velocity block at char [" + i + "]", e);
                context.setVelocity(false);
            }

            if (context.isVelocity()) {
                if (!inVelocityMacro) {
                    inVelocityMacro = true;
                } else {
                    velocityBuffer.append(afterVelocityBuffer);
                    afterVelocityBuffer.setLength(0);
                }

                if (context.isConversion()) {
                    if (!context.isInline()) {
                        if (velocityBuffer.length() > 0) {
                            CleanUtil.setTrailingNewLines(velocityBuffer, 2);
                        }
                    }
                }

                velocityBuffer.append(context.isProtectedBlock() ? filterContext.addProtectedContent(velocityBlock
                    .toString(), VELOCITY_SUFFIX + context.getType(), context.isInline()) : velocityBlock);
            } else {
                StringBuffer nonVelocityBuffer = inVelocityMacro ? afterVelocityBuffer : beforeVelocityBuffer;

                if (context.isConversion()) {
                    if (!context.isInline()) {
                        if (nonVelocityBuffer.length() > 0 || velocityBuffer.length() > 0) {
                            CleanUtil.setTrailingNewLines(nonVelocityBuffer, 2);
                        }
                    }

                    nonVelocityBuffer.append(context.isProtectedBlock() ? filterContext.addProtectedContent(
                        velocityBlock.toString(), context.isInline()) : velocityBlock);
                } else {
                    nonVelocityBuffer.append(c);
                    ++i;
                }
            }
        }

        // fix not closed #if, #foreach
        if (context.getVelocityDepth() > 0) {
            velocityBuffer.append(afterVelocityBuffer);
            afterVelocityBuffer.setLength(0);

            // fix unclosed velocity blocks
            for (; context.getVelocityDepth() > 0; context.popVelocityDepth()) {
                velocityBuffer.append(filterContext.addProtectedContent("#end"));
            }
        }

        if (velocityBuffer.length() > 0) {
            String beforeVelocityContent = beforeVelocityBuffer.toString();
            String velocityContent = velocityBuffer.toString();
            String unProtectedVelocityContent = filterContext.unProtect(velocityContent);
            String afterVelocityContent = afterVelocityBuffer.toString();

            boolean multilines = unProtectedVelocityContent.indexOf("\n") != -1;

            // print before velocity content
            result.append(beforeVelocityContent);

            // print velocity content
            appendVelocityOpen(result, filterContext, multilines);
            result.append(velocityContent);
            appendVelocityClose(result, filterContext, multilines);

            // print after velocity content
            result.append(afterVelocityContent);
        } else {
            result = beforeVelocityBuffer;
        }

        return result.toString();
    }

    public static void appendVelocityOpen(StringBuffer result, FilterContext filterContext, boolean nl)
    {
        result.append(filterContext.addProtectedContent("{{velocity filter=\"none\"}}" + (nl ? "\n" : ""),
            VELOCITYOPEN_SUFFIX, true));
    }

    public static void appendVelocityClose(StringBuffer result, FilterContext filterContext, boolean nl)
    {
        result
            .append(filterContext.addProtectedContent((nl ? "\n" : "") + "{{/velocity}}", VELOCITYCLOSE_SUFFIX, true));
    }
}
