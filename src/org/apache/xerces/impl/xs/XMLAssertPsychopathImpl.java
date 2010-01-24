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

package org.apache.xerces.impl.xs;

import java.util.Map;
import java.util.Stack;
import java.util.Vector;

import org.apache.xerces.dom.CoreDocumentImpl;
import org.apache.xerces.dom.PSVIAttrNSImpl;
import org.apache.xerces.dom.PSVIDocumentImpl;
import org.apache.xerces.dom.PSVIElementNSImpl;
import org.apache.xerces.impl.Constants;
import org.apache.xerces.impl.xs.assertion.XSAssertImpl;
import org.apache.xerces.xni.Augmentations;
import org.apache.xerces.xni.QName;
import org.apache.xerces.xni.XMLAttributes;
import org.apache.xerces.xni.XMLString;
import org.apache.xerces.xni.parser.XMLAssertAdapter;
import org.apache.xerces.xs.ElementPSVI;
import org.apache.xerces.xs.XSConstants;
import org.apache.xerces.xs.XSModel;
import org.apache.xerces.xs.XSObjectList;
import org.apache.xerces.xs.XSTypeDefinition;
import org.eclipse.wst.xml.xpath2.processor.DynamicContext;
import org.eclipse.wst.xml.xpath2.processor.DynamicError;
import org.eclipse.wst.xml.xpath2.processor.StaticError;
import org.eclipse.wst.xml.xpath2.processor.ast.XPath;
import org.eclipse.wst.xml.xpath2.processor.internal.types.AnyAtomicType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * An implementation of the XPath interface, for XML Schema 1.1 'assertions'
 * evaluation. This class interfaces with the PsychoPath XPath 2.0 engine.
 * 
 * @xerces.internal
 * 
 * @author Mukul Gandhi, IBM
 * @author Ken Cai, IBM
 * 
 * @version $Id$
 */
public class XMLAssertPsychopathImpl extends XMLAssertAdapter {

    // class variable declarations
    DynamicContext fDynamicContext;
    XSModel fSchema = null;
    AbstractPsychoPathImpl abstrPsychopathImpl = null;

    // a factory Document object to construct DOM tree nodes
    Document assertDocument = null;

    // an element to track construction of assertion DOM tree. This object
    // changes as per the XNI document events.
    Element currentAssertDomNode = null;

    // a stack holding the DOM root for assertions evaluation
    Stack assertRootStack = null;

    // a stack parallel to 'assertRootStack' storing all assertions for a
    // single assert tree.
    Stack assertListStack = null;

    // XMLSchemaValidator reference. set from the XMLSchemaValidator object
    // itself.
    XMLSchemaValidator validator = null;
    
    // parameters to pass to PsychoPath engine (like, namespace bindings) 
    Map assertParams = null;

    /*
     * The class constructor
     */
    public XMLAssertPsychopathImpl(Map assertParams) {
        // initializing the class variables.        
        // we use the PSVI enabled DOM implementation, so as to have typed
        // XDM nodes.
        this.assertDocument = new PSVIDocumentImpl();        
        this.assertRootStack = new Stack();
        this.assertListStack = new Stack();
        this.assertParams = assertParams;  
    }

    /*
     * Initialize the PsychoPath XPath processor
     */
    private void initXPathProcessor() throws Exception {
        validator = (XMLSchemaValidator) getProperty
                        ("http://apache.org/xml/properties/assert/validator");        
        abstrPsychopathImpl = new AbstractPsychoPathImpl();
        fDynamicContext = abstrPsychopathImpl.initDynamicContext(
                                                    fSchema,
                                                    assertDocument,
                                                    assertParams);
    }

    /*
     * (non-Javadoc)
     * @see org.apache.xerces.xni.parser.XMLAssertAdapter#startElement(org.apache.xerces.xni.QName, org.apache.xerces.xni.XMLAttributes, org.apache.xerces.xni.Augmentations)
     */
    public void startElement(QName element, XMLAttributes attributes,
                                              Augmentations augs) {
        if (currentAssertDomNode == null) {
           currentAssertDomNode = new PSVIElementNSImpl((CoreDocumentImpl)
                                assertDocument, element.uri, element.rawname);
           assertDocument.appendChild(currentAssertDomNode);
        } else {
            Element elem = new PSVIElementNSImpl((CoreDocumentImpl)
                                assertDocument, element.uri, element.rawname);
            currentAssertDomNode.appendChild(elem);
            currentAssertDomNode = elem;
        }

        // add attributes to the element
        for (int attIndex = 0; attIndex < attributes.getLength(); attIndex++) {
            String attrUri = attributes.getURI(attIndex);
            String attQName = attributes.getQName(attIndex);
            String attValue = attributes.getValue(attIndex);
            
            PSVIAttrNSImpl attrNode = new PSVIAttrNSImpl((PSVIDocumentImpl)
                                          assertDocument, attrUri, attQName);
            attrNode.setNodeValue(attValue);
            
            // set PSVI information for the attribute
            Augmentations attrAugs = attributes.getAugmentations(attIndex);
            AttributePSVImpl attrPSVI = (AttributePSVImpl) attrAugs.
                                         getItem(Constants.ATTRIBUTE_PSVI);
            attrNode.setPSVI(attrPSVI);
            
            currentAssertDomNode.setAttributeNode(attrNode);
        }

        Object assertion = augs.getItem("ASSERT");
        // if we have assertion applicable to this element, store the element
        // reference and the assertions on it, on the runtime stacks
        if (assertion != null) {
            assertRootStack.push(currentAssertDomNode);
            assertListStack.push(assertion);
        }
    }

    /*
     * (non-Javadoc)
     * @see org.apache.xerces.xni.parser.XMLAssertAdapter#endElement(org.apache.xerces.xni.QName, org.apache.xerces.xni.Augmentations)
     */
    public void endElement(QName element, Augmentations augs) throws Exception {
        if (currentAssertDomNode != null) {
            // set PSVI information on the element
            ElementPSVI elemPSVI = (ElementPSVI) augs.getItem(
                                                 Constants.ELEMENT_PSVI);
            ((PSVIElementNSImpl)currentAssertDomNode).setPSVI(elemPSVI);
            
            if (!assertRootStack.empty() && (currentAssertDomNode == 
                                             assertRootStack.peek())) {               
                 // get XSModel                
                 fSchema =  ((PSVIElementNSImpl) currentAssertDomNode).
                                                 getSchemaInformation();
                 
                 // pop the stack, to go one level up
                 assertRootStack.pop();
                 // get assertions, and go one level up
                 Object assertions = assertListStack.pop(); 
                 
                 processAllAssertionsOnElement(element, assertions);
            }

            if (currentAssertDomNode.getParentNode() instanceof Element) {
              currentAssertDomNode = (Element)currentAssertDomNode.
                                                       getParentNode();
            }
        }
    }

    /*
     * Method to evaluate all assertions for the element tree
     */
    private void processAllAssertionsOnElement(
                                    QName element,
                                    Object assertions)
                                    throws Exception {
         // initialize the XPath engine
         initXPathProcessor();
         
         // determine value of variable, $value
         String value = "";
         NodeList childList = currentAssertDomNode.getChildNodes();         
         int textChildCount = 0;
         // there could be adjacent text nodes. merge them to get the value.
         for (int childNodeIndex = 0; childNodeIndex < childList.getLength();
                                                       childNodeIndex++) {
             Node node = childList.item(childNodeIndex);
             if (node.getNodeType() == Node.TEXT_NODE) {
                 textChildCount++;
                 value = value + node.getNodeValue();
             }
         }
         
         if (!(textChildCount > 0 && (textChildCount ==
                                      childList.getLength()))) {
            value = null;  
         }

         // evaluate assertions
         if (assertions instanceof XSObjectList) {
            // assertions from a complex type definition
            if (value != null) {
              // complex type with simple content
              setValueOf$value(value, null);
            } else {
              // complex type with complex content                
              // $value should be, the XPath2 "empty sequence" ... TO DO 
            }
            XSObjectList assertList = (XSObjectList) assertions;
            for (int i = 0; i < assertList.size(); i++) {
               XSAssertImpl assertImpl = (XSAssertImpl) assertList.get(i);               
               boolean xpathContextExists = false;
               if (assertImpl.getType() == XSConstants.ASSERTION) {
                  // not an assertion facet
                  xpathContextExists = true;   
               }
               // check if this is an assertion, from the attribute
               if (assertImpl.getAttrValue() != null) {
                  // reassign value (the attribute's value) to variable
                  // $value.
                  value = assertImpl.getAttrValue();
                  setValueOf$value(value, assertImpl.getTypeDefinition());
               }
               evaluateAssertion(element,
                                 assertImpl,
                                 value,
                                 xpathContextExists);
            }
         }
         else if (assertions instanceof Vector) {
            // assertions from a simple type definition
            setValueOf$value(value, null);
            Vector assertList = (Vector) assertions;                    
            for (int i = 0; i < assertList.size(); i++) {
                XSAssertImpl assertImpl = (XSAssertImpl) assertList.get(i);
                evaluateAssertion(element, assertImpl, value, false);
            }
         }
    }

    /*
     * (non-Javadoc)
     * @see org.apache.xerces.xni.parser.XMLAssertAdapter#characters(org.apache.xerces.xni.XMLString)
     */
    public void characters(XMLString text) {
        // add a child text node to the assertions, DOM tree
        if (currentAssertDomNode != null) {
            currentAssertDomNode.appendChild(assertDocument.createTextNode(new 
                                   String(text.ch, text.offset, text.length)));
        }
    }

    /*
     * Method to evaluate an assertion for the element
     */
    private void evaluateAssertion(QName element,
                                   XSAssertImpl assertImpl,
                                   String value,
                                   boolean xPathContextExists) {
        try {  
            XPath xp = assertImpl.getCompiledXPath();
            
            boolean result;            
            if ((value == null) ||
                (xPathContextExists == true)) {
                result = abstrPsychopathImpl.evaluatePsychoPathExpr(xp,
                                 assertImpl.getXPathDefaultNamespace(),
                                 currentAssertDomNode);  
            } 
            else {
                // XPath context is "undefined"
                result = abstrPsychopathImpl.evaluatePsychoPathExpr(xp,
                                 assertImpl.getXPathDefaultNamespace(),
                                 null); 
            }
            
            if (!result) {
               // assertion evaluation is false
                if (assertImpl.getAttrName() == null) {
                   reportError("cvc-assertion.3.13.4.1", element, assertImpl);
                }
                else {
                   reportError("cvc-assertion.3.13.4.1", element, assertImpl); 
                }
            }
        }
        catch (DynamicError ex) {
            if (ex.code().equals("XPDY0002")) {
               // ref: http://www.w3.org/TR/xpath20/#eval_context
               reportError("cvc-assertion.4.3.15.3", element, assertImpl);
            }
            else {
               reportError("cvc-assertion.3.13.4.1", element, assertImpl);  
            }
        }
        catch (StaticError ex) {
            reportError("cvc-assertion.3.13.4.1", element, assertImpl);
        }
    }
    
    /*
     * Method to report error messages
     */
    private void reportError(String key, QName element,
                             XSAssertImpl assertImpl) {
        XSTypeDefinition typeDef = assertImpl.getTypeDefinition();
        String typeString = "";
        
        if (typeDef != null) {
           typeString = (typeDef.getName() != null) ? typeDef.getName() :
                                                      "#anonymous";   
        }
        else {
           typeString = "#anonymous"; 
        }
        
        String elemErrorAnnotation = element.rawname;
        if (assertImpl.getAttrName() != null) {
           elemErrorAnnotation = element.rawname + " (attribute => " +
                                              assertImpl.getAttrName()+ ")";    
        }
        
        validator.reportSchemaError(key, new Object[] { elemErrorAnnotation,
                               assertImpl.getTest().getXPath().toString(),
                               typeString } );
    }
    
    // assign value to the XPath2 "dynamic context" variable, $value
    private void setValueOf$value(String value, XSTypeDefinition attrType) {
        String xsdTypeName = "";
        
        if (attrType != null) {
           // is value of an attribute
           xsdTypeName = getXSDtypeOf$Value(attrType);  
        }
        else {
          // is "simple type" value of an element
          PSVIElementNSImpl currentAssertPSVINode = (PSVIElementNSImpl)
                                                 currentAssertDomNode;
       
          xsdTypeName = getXSDtypeOf$Value(currentAssertPSVINode.
                                               getTypeDefinition()); 
        }
        
        Object psychoPathType = abstrPsychopathImpl.getPsychoPathTypeForXSDType
                                                         (xsdTypeName, value);       
        fDynamicContext.set_variable(
               new org.eclipse.wst.xml.xpath2.processor.internal.types.QName(
                       "value"), (AnyAtomicType) psychoPathType);
    }
    
    /*
       Find the built in XSD type for XPath2 variable, $value. This function
       recursively searches the XSD type hierarchy navigating up the base
       types, to find the needed built-in type.
    */
    private String getXSDtypeOf$Value(XSTypeDefinition elementType) {
      if (Constants.NS_XMLSCHEMA.equals(elementType.getNamespace())) {
        return elementType.getName();    
      }
      else {
        return getXSDtypeOf$Value(elementType.getBaseType()); 
      }
    }
}