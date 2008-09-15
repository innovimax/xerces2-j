/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.xerces.impl.xs.models;

import org.apache.xerces.xni.QName;
import org.apache.xerces.impl.xs.XSElementDecl;
import org.apache.xerces.impl.xs.SubstitutionGroupHandler;
import org.apache.xerces.impl.xs.XMLSchemaException;
import org.apache.xerces.impl.xs.XSConstraints;
import org.apache.xerces.impl.xs.XSParticleDecl;
import org.apache.xerces.impl.xs.XSWildcardDecl;
import org.apache.xerces.impl.xs.XSOpenContentDecl;

import java.util.Vector;

/**
 * XSAll11CM implements XSCMValidator and handles &lt;all&gt;.
 *
 * @xerces.internal
 *
 * @author Khaled Noaman, IBM
 * @version $Id$
 */
public class XS11AllCM implements XSCMValidator {

    //
    // Constants
    //

    // start the content model: did not see any children
    private static final short STATE_START = 0;
    private static final short STATE_CHILD = 1;
    private static final short STATE_SUFFIX = 2;

    //
    // Data
    //
    private boolean fHasOptionalContent = false;

    private Object fAllDecls[];
    private int fDeclsOccurs[];
    private int fNumDecls = 0;
    private int fNumElements = 0;

    private XSOpenContentDecl fOpenContent = null;

    //
    // Constructors
    //

    public XS11AllCM (boolean hasOptionalContent, int size, XSOpenContentDecl openContent) {
        fHasOptionalContent = hasOptionalContent;
        fAllDecls = new Object[size];
        fDeclsOccurs = new int[size << 1];
        fOpenContent = openContent;
    }

    public void addElement(Object element, int elementType, int minOccurs, int maxOccurs) {
        int occurenceStart;
        if (elementType == XSParticleDecl.PARTICLE_ELEMENT) {
            if (fNumDecls > fNumElements) {
                for (int i = fNumDecls, j = i << 1; i > fNumElements; i--, j -= 2) {
                    fAllDecls[i] = fAllDecls[i-1];
                    fDeclsOccurs[j] = fDeclsOccurs[j - 2];
                    fDeclsOccurs[j + 1] = fDeclsOccurs[j - 1];
                }
            }
            occurenceStart = fNumElements << 1;
            fAllDecls[fNumElements] = element;
            fNumElements++;
        }
        else {
            occurenceStart = fNumDecls << 1;
            fAllDecls[fNumDecls] = element;
        }

        fDeclsOccurs[occurenceStart] = minOccurs;
        fDeclsOccurs[occurenceStart + 1] = maxOccurs;
        fNumDecls++;
    }

    //
    // XSCMValidator methods
    //

    /**
     * This methods to be called on entering a first element whose type
     * has this content model. It will return the initial state of the
     * content model
     *
     * @return Start state of the content model
     */
    public int[] startContentModel() {
        int[] state = new int[fNumDecls + 1];
        for (int i = 0; i <= fNumDecls; i++) {
            state[i] = STATE_START;
        }
        return state;
    }

    // convinient method: when error occurs, to find a matching decl
    // from the candidate elements.
    Object findMatchingDecl(QName elementName, SubstitutionGroupHandler subGroupHandler) {
        Object matchingDecl = null;

        for (int i = 0; i < fNumElements; i++) {
            matchingDecl = subGroupHandler.getMatchingElemDecl(elementName, (XSElementDecl)fAllDecls[i]);
            if (matchingDecl != null)
                return matchingDecl;
        }

        for (int i = fNumElements; i < fNumDecls; i++) {
            if (((XSWildcardDecl)fAllDecls[i]).allowQName(elementName)) {
                matchingDecl = fAllDecls[i];
                break;
            }
        }

        return matchingDecl;
    }

    /**
     * The method corresponds to one transition in the content model.
     *
     * @param elementName
     * @param currentState  Current state
     * @return an element decl object
     */
    public Object oneTransition (QName elementName, int[] currentState, SubstitutionGroupHandler subGroupHandler) {
        // error state
        if (currentState[0] < 0) {
            currentState[0] = XSCMValidator.SUBSEQUENT_ERROR;
            return findMatchingDecl(elementName, subGroupHandler);
        }

        // open content - suffix mode
        if (currentState[0] == STATE_SUFFIX) {
            if (fOpenContent.fWildcard.allowQName(elementName)) {
                return fOpenContent;
            }
            else { // error
                currentState[0] = XSCMValidator.FIRST_ERROR;
                return findMatchingDecl(elementName, subGroupHandler);
            }
        }

        // seen child
        currentState[0] = STATE_CHILD;

        for (int i = 0; i < fNumElements; i++) {
            int declMaxOccurs  = (i << 1) + 1;
            if (currentState[i + 1] == fDeclsOccurs[declMaxOccurs]) {
                continue;
            }
            Object matchingDecl = subGroupHandler.getMatchingElemDecl(elementName, (XSElementDecl)fAllDecls[i]);
            if (matchingDecl != null) {
                // found the decl, mark this element as "seen".
                ++currentState[i + 1];
                return matchingDecl;
            }
        }

        for (int i = fNumElements; i < fNumDecls; i++) {
            int declMaxOccurs = (i << 1) + 1;
            if (currentState[i + 1] == fDeclsOccurs[declMaxOccurs]) {
                continue;
            }
            if (((XSWildcardDecl)fAllDecls[i]).allowQName(elementName)) {
                // found the decl, mark this element as "seen".
                ++currentState[i + 1];
                return fAllDecls[i];
            }
        }

        // apply open content, if present
        if (fOpenContent != null) {
            if (fOpenContent.fMode == XSOpenContentDecl.MODE_SUFFIX) {
                if (isFinal(currentState)) {
                    currentState[0] = STATE_SUFFIX;
                }
                else {
                    currentState[0] = XSCMValidator.FIRST_ERROR;
                    return findMatchingDecl(elementName, subGroupHandler);
                }
            }
            if (fOpenContent.fWildcard.allowQName(elementName)) {
                return fOpenContent;
            }
        }

        // couldn't find the decl, change to error state.
        currentState[0] = XSCMValidator.FIRST_ERROR;
        return findMatchingDecl(elementName, subGroupHandler);
    }

    /**
     * The method indicates the end of list of children
     *
     * @param currentState  Current state of the content model
     * @return true if the last state was a valid final state
     */
    public boolean endContentModel (int[] currentState) {
        int state = currentState[0];

        if (state == XSCMValidator.FIRST_ERROR || state == XSCMValidator.SUBSEQUENT_ERROR) {
            return false;
        }

        // If <all> has minOccurs of zero and there are
        // no children to validate, it is trivially valid
        //
        // XML Schema 1.1
        // reached final state and doing suffix validation against open content
        if ((fHasOptionalContent && state == STATE_START) || state == STATE_SUFFIX) {
            return true;
        }

        for (int i = 0; i < fNumDecls; i++) {
            // if one element is required, but not present, then error
            if (currentState[i+1] < fDeclsOccurs[i << 1]) {
                return false;
            }
        }

        return true;
    }

    /**
     * check whether this content violates UPA constraint.
     *
     * @param subGroupHandler the substitution group handler
     * @return true if this content model contains other or list wildcard
     */
    public boolean checkUniqueParticleAttribution(SubstitutionGroupHandler subGroupHandler) throws XMLSchemaException {
        // check whether there is conflict between any two leaves
        for (int i = 0; i < fNumElements; i++) {
            for (int j = i+1; j < fNumElements; j++) {
                // TODO: apply 1.1 rules
                if (XSConstraints.overlapUPA((XSElementDecl)fAllDecls[i], (XSElementDecl)fAllDecls[j], subGroupHandler)) {
                    // REVISIT: do we want to report all errors? or just one?
                    throw new XMLSchemaException("cos-nonambig", new Object[]{fAllDecls[i].toString(),
                                                                              fAllDecls[j].toString()});
                }
            }
        }
        for (int i = fNumElements; i < fNumDecls; i++) {
            for (int j = i+1; j < fNumDecls; j++) {
                // TODO: apply 1.1 rules
                if (XSConstraints.overlapUPA((XSWildcardDecl)fAllDecls[i], (XSWildcardDecl)fAllDecls[j])) {
                    // REVISIT: do we want to report all errors? or just one?
                    throw new XMLSchemaException("cos-nonambig", new Object[]{fAllDecls[i].toString(),
                                                                              fAllDecls[j].toString()});
                }
            }
        }
        return false;
    }

    /**
     * Check which elements are valid to appear at this point. This method also
     * works if the state is in error, in which case it returns what should
     * have been seen.
     *
     * @param state  the current state
     * @return       a Vector whose entries are instances of
     *               either XSWildcardDecl or XSElementDecl.
     */
    public Vector whatCanGoHere(int[] state) {
        Vector ret = new Vector();

        // handle element declarations
        for (int i = 0; i < fNumElements; i++) {
            // we only try to look for a matching decl if we have not seen
            // this element yet or we have seen it less times than its maxOccurs.
            if (state[i+1] == 0 || state[i+1] < fDeclsOccurs[(i << 1) + 1]) {
                ret.addElement(fAllDecls[i]);
            }
        }

        // only add wildcards if no element can be matched
        if (ret.size() == 0) {
            for (int i = fNumElements; i < fNumDecls; i++) {
                if (state[i+1] == 0 || state[i+1] < fDeclsOccurs[(i << 1) + 1]) {
                    ret.addElement(fAllDecls[i]);
                }
            }
        }

        // if 'ret' is empty and we have an open content, add the open content to 'ret'
        if (ret.size() == 0 && fOpenContent != null) {
            ret.add(fOpenContent);
        }
        return ret;
    }

    public boolean isCompactedForUPA() {
        return false;
    }

    private boolean isFinal(int[] currentState) {
        for (int i = 0; i < fNumDecls; i++) {
            // if one element is required, but not present, then error
            if (currentState[i+1] < fDeclsOccurs[i << 1]) {
                return false;
            }
        }

        return true;
    }

} // class XSAll11CM
