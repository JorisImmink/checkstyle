////////////////////////////////////////////////////////////////////////////////
// checkstyle: Checks Java source code for adherence to a set of rules.
// Copyright (C) 2001-2004  Oliver Burn
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
////////////////////////////////////////////////////////////////////////////////
package com.puppycrawl.tools.checkstyle.checks.coding;

import java.util.HashSet;
import java.util.Set;

import org.apache.commons.beanutils.ConversionException;
import org.apache.regexp.RE;
import org.apache.regexp.RESyntaxException;

import com.puppycrawl.tools.checkstyle.api.Check;
import com.puppycrawl.tools.checkstyle.api.DetailAST;
import com.puppycrawl.tools.checkstyle.api.ScopeUtils;
import com.puppycrawl.tools.checkstyle.api.TokenTypes;
import com.puppycrawl.tools.checkstyle.api.Utils;

/**
 * <p>Checks that a local variable or a parameter does not shadow
 * a field that is defined in the same class.
 * </p>
 * <p>
 * An example of how to configure the check is:
 * </p>
 * <pre>
 * &lt;module name="HiddenField"/&gt;
 * </pre>
 * <p>
 * An example of how to configure the check so that it checks variables but not
 * parameters is:
 * </p>
 * <pre>
 * &lt;module name="HiddenField"&gt;
 *    &lt;property name="tokens" value="VARIABLE_DEF"/&gt;
 * &lt;/module&gt;
 * </pre>
 * <p>
 * An example of how to configure the check so that it ignores the parameter of
 * a setter method is:
 * </p>
 * <pre>
 * &lt;module name="HiddenField"&gt;
 *    &lt;property name="ignoreSetter" value="true"/&gt;
 * &lt;/module&gt;
 * </pre>
 * <p>
 * An example of how to configure the check so that it ignores constructor
 * parameters is:
 * </p>
 * <pre>
 * &lt;module name="HiddenField"&gt;
 *    &lt;property name="ignoreConstructorParameter" value="true"/&gt;
 * &lt;/module&gt;
 * </pre>


 * @author Rick Giles
 * @version 1.0
 */
public class HiddenFieldCheck
    extends Check
{
    /** stack of sets of field names,
     * one for each class of a set of nested classes.
     */
    private FieldFrame mCurrentFrame;

    /** the regexp to match against */
    private RE mRegexp;

    /** controls whether to check the parameter of a property setter method */
    private boolean mIgnoreSetter;

    /** controls whether to check the parameter of a constructor */
    private boolean mIgnoreConstructorParameter;

    /** @see com.puppycrawl.tools.checkstyle.api.Check */
    public int[] getDefaultTokens()
    {
        return new int[] {
            TokenTypes.VARIABLE_DEF,
            TokenTypes.PARAMETER_DEF,
            TokenTypes.CLASS_DEF,
        };
    }

    /** @see com.puppycrawl.tools.checkstyle.api.Check */
    public int[] getAcceptableTokens()
    {
        return new int[] {
            TokenTypes.VARIABLE_DEF,
            TokenTypes.PARAMETER_DEF,
        };
    }

    /** @see com.puppycrawl.tools.checkstyle.api.Check */
    public int[] getRequiredTokens()
    {
        return new int[] {
            TokenTypes.CLASS_DEF,
        };
    }

    /** @see com.puppycrawl.tools.checkstyle.api.Check */
    public void beginTree(DetailAST aRootAST)
    {
        mCurrentFrame = new FieldFrame(null, true);
    }

    /** @see com.puppycrawl.tools.checkstyle.api.Check */
    public void visitToken(DetailAST aAST)
    {
        if (aAST.getType() == TokenTypes.CLASS_DEF) {
            //find and push fields
            final DetailAST classMods =
                aAST.findFirstToken(TokenTypes.MODIFIERS);
            final boolean isStaticInnerClass =
                classMods.branchContains(TokenTypes.LITERAL_STATIC);
            final FieldFrame frame =
                new FieldFrame(mCurrentFrame, isStaticInnerClass);
            //add fields to container
            final DetailAST objBlock =
                aAST.findFirstToken(TokenTypes.OBJBLOCK);
            DetailAST child = (DetailAST) objBlock.getFirstChild();
            while (child != null) {
                if (child.getType() == TokenTypes.VARIABLE_DEF) {
                    final String name =
                        child.findFirstToken(TokenTypes.IDENT).getText();
                    final DetailAST mods =
                        child.findFirstToken(TokenTypes.MODIFIERS);
                    if (mods.branchContains(TokenTypes.LITERAL_STATIC)) {
                        frame.addStaticField(name);
                    }
                    else {
                        frame.addInstanceField(name);
                    }
                }
                child = (DetailAST) child.getNextSibling();
            }
            // push container
            mCurrentFrame = frame;
        }
        else {
            //must be VARIABLE_DEF or PARAMETER_DEF
            processVariable(aAST);
        }
    }

    /** @see com.puppycrawl.tools.checkstyle.api.Check */
    public void leaveToken(DetailAST aAST)
    {
        if (aAST.getType() == TokenTypes.CLASS_DEF) {
            //pop
            mCurrentFrame = mCurrentFrame.getParent();
        }
    }

    /**
     * Process a variable token.
     * Check whether a local variable or parameter shadows a field.
     * Store a field for later comparison with local variables and parameters.
     * @param aAST the variable token.
     */
    private void processVariable(DetailAST aAST)
    {
        if (!ScopeUtils.inInterfaceBlock(aAST)) {
            if (ScopeUtils.isLocalVariableDef(aAST)
                || (aAST.getType() == TokenTypes.PARAMETER_DEF))
            {
                //local variable or parameter. Does it shadow a field?
                final DetailAST nameAST = aAST.findFirstToken(TokenTypes.IDENT);
                final String name = nameAST.getText();
                if ((mCurrentFrame.containsStaticField(name)
                     || (!inStatic(aAST)
                         && mCurrentFrame.containsInstanceField(name)))
                    && ((mRegexp == null) || (!getRegexp().match(name)))
                    && !isIgnoredSetterParam(aAST, name)
                    && !isIgnoredConstructorParam(aAST))
                {
                    log(nameAST, "hidden.field", name);
                }
            }
        }
    }

    /**
     * Determines whether an AST node is in a static method or static
     * initializer.
     * @param aAST the node to check.
     * @return true if aAST is in a static method or a static block;
     */
    private boolean inStatic(DetailAST aAST)
    {
        DetailAST parent = aAST.getParent();
        while (parent != null) {
            switch (parent.getType()) {
            case TokenTypes.STATIC_INIT:
                return true;
            case TokenTypes.METHOD_DEF:
                final DetailAST mods =
                    parent.findFirstToken(TokenTypes.MODIFIERS);
                return mods.branchContains(TokenTypes.LITERAL_STATIC);
            default:
                parent = parent.getParent();
            }
        }
        return false;
    }

    /**
     * Decides whether to ignore an AST node that is the parameter of a
     * setter method, where the property setter method for field 'xyz' has
     * name 'setXyz', one parameter named 'xyz', and return type void.
     * @param aAST the AST to check.
     * @param aName the name of aAST.
     * @return true if aAST should be ignored because check property
     * ignoreSetter is true and aAST is the parameter of a setter method.
     */
    private boolean isIgnoredSetterParam(DetailAST aAST, String aName)
    {
        if (!(aAST.getType() == TokenTypes.PARAMETER_DEF)
            || !mIgnoreSetter)
        {
            return false;
        }
        //single parameter?
        final DetailAST parametersAST = aAST.getParent();
        if (parametersAST.getChildCount() != 1) {
            return false;
        }
        //method parameter, not constructor parameter?
        final DetailAST methodAST = parametersAST.getParent();
        if (methodAST.getType() != TokenTypes.METHOD_DEF) {
            return false;
        }
        //property setter name?
        final String expectedName =
            "set" + aName.substring(0, 1).toUpperCase() + aName.substring(1);
        final DetailAST methodNameAST =
            methodAST.findFirstToken(TokenTypes.IDENT);
        final String methodName = methodNameAST.getText();
        if (!methodName.equals(expectedName)) {
            return false;
        }
        //void?
        final DetailAST typeAST = methodAST.findFirstToken(TokenTypes.TYPE);
        return typeAST.branchContains(TokenTypes.LITERAL_VOID);
    }

    /**
     * Decides whether to ignore an AST node that is the parameter of a
     * constructor.
     * @param aAST the AST to check.
     * @return true if aAST should be ignored because check property
     * ignoreConstructorParameter is true and aAST is a constructor parameter.
     */
    private boolean isIgnoredConstructorParam(DetailAST aAST)
    {
        if (!(aAST.getType() == TokenTypes.PARAMETER_DEF)
            || !mIgnoreConstructorParameter)
        {
            return false;
        }
        final DetailAST parametersAST = aAST.getParent();
        final DetailAST constructorAST = parametersAST.getParent();
        return (constructorAST.getType() == TokenTypes.CTOR_DEF);
    }

    /**
     * Set the ignore format to the specified regular expression.
     * @param aFormat a <code>String</code> value
     * @throws ConversionException unable to parse aFormat
     */
    public void setIgnoreFormat(String aFormat)
        throws ConversionException
    {
        try {
            mRegexp = Utils.getRE(aFormat);
        }
        catch (RESyntaxException e) {
            throw new ConversionException("unable to parse " + aFormat, e);
        }
    }

    /**
     * Set whether to ignore the parameter of a property setter method.
     * @param aIgnoreSetter decide whether to ignore the parameter of
     * a property setter method.
     */
    public void setIgnoreSetter(boolean aIgnoreSetter)
    {
        mIgnoreSetter = aIgnoreSetter;
    }

    /**
     * Set whether to ignore constructor parameters.
     * @param aIgnoreConstructorParameter decide whether to ignore
     * constructor parameters.
     */
    public void setIgnoreConstructorParameter(
        boolean aIgnoreConstructorParameter)
    {
        mIgnoreConstructorParameter = aIgnoreConstructorParameter;
    }

    /** @return the regexp to match against */
    public RE getRegexp()
    {
        return mRegexp;
    }

    /**
     * Holds the names of static and instance fields of a class.
     * @author Rick Giles
     * Describe class FieldFrame
     * @author Rick Giles
     * @version Oct 26, 2003
     */
    private static class FieldFrame
    {
        /** is this a static inner class */
        private boolean mStaticClass;

        /** parent frame. */
        private FieldFrame mParent;

        /** set of instance field names */
        private Set mInstanceFields = new HashSet();

        /** set of static field names */
        private Set mStaticFields = new HashSet();

        /** Creates new frame.
         * @param aStaticClass is this a static inner class.
         * @param aParent parent frame.
         */
        public FieldFrame(FieldFrame aParent, boolean aStaticClass)
        {
            mParent = aParent;
            mStaticClass = aStaticClass;
        }

        /**
         * Adds an instance field to this FieldFrame.
         * @param aField  the name of the instance field.
         */
        public void addInstanceField(String aField)
        {
            mInstanceFields.add(aField);
        }

        /**
         * Adds a static field to this FieldFrame.
         * @param aField  the name of the instance field.
         */
        public void addStaticField(String aField)
        {
            mStaticFields.add(aField);
        }

        /**
         * Determines whether this FieldFrame contains an instance field.
         * @param aField the field to check.
         * @return true if this FieldFrame contains instance field aField.
         */
        public boolean containsInstanceField(String aField)
        {
            if (mInstanceFields.contains(aField)) {
                return true;
            }
            if (mStaticClass) {
                return false;
            }

            return (mParent != null) && mParent.containsInstanceField(aField);
        }

        /**
         * Determines whether this FieldFrame contains a static field.
         * @param aField the field to check.
         * @return true if this FieldFrame contains static field aField.
         */
        public boolean containsStaticField(String aField)
        {
            if (mStaticFields.contains(aField)) {
                return true;
            }

            return (mParent != null) && mParent.containsStaticField(aField);
        }

        /**
         * Getter for parent frame.
         * @return parent frame.
         */
        public FieldFrame getParent()
        {
            return mParent;
        }
    }
}
