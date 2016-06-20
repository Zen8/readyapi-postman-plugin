package com.smartbear.postman;

import com.eviware.soapui.impl.WsdlInterfaceFactory;
import com.eviware.soapui.impl.actions.ProRestServiceBuilder;
import com.eviware.soapui.impl.actions.ProRestServiceBuilder.RequestInfo;
import com.eviware.soapui.impl.rest.RestMethod;
import com.eviware.soapui.impl.rest.RestRequest;
import com.eviware.soapui.impl.rest.RestRequestInterface;
import com.eviware.soapui.impl.rest.RestResource;
import com.eviware.soapui.impl.rest.support.RestParamProperty;
import com.eviware.soapui.impl.rest.support.RestParamsPropertyHolder;
import com.eviware.soapui.impl.support.AbstractHttpRequest;
import com.eviware.soapui.impl.wsdl.WsdlInterface;
import com.eviware.soapui.impl.wsdl.WsdlOperation;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.impl.wsdl.WsdlProjectPro;
import com.eviware.soapui.impl.wsdl.WsdlRequest;
import com.eviware.soapui.impl.wsdl.WsdlTestSuite;
import com.eviware.soapui.impl.wsdl.testcase.WsdlTestCase;
import com.eviware.soapui.impl.wsdl.teststeps.RestTestRequestStep;
import com.eviware.soapui.impl.wsdl.teststeps.WsdlTestRequestStep;
import com.eviware.soapui.impl.wsdl.teststeps.WsdlTestStep;
import com.eviware.soapui.model.testsuite.Assertable;
import com.eviware.soapui.model.testsuite.TestProperty;
import com.eviware.soapui.support.JsonUtil;
import com.eviware.soapui.support.SoapUIException;
import com.eviware.soapui.support.StringUtils;
import com.eviware.soapui.support.types.StringToStringsMap;
import com.eviware.soapui.support.xml.XmlUtils;
import com.smartbear.postman.script.PostmanScriptParser;
import com.smartbear.postman.script.PostmanScriptTokenizer;
import com.smartbear.postman.script.PostmanScriptTokenizer.Token;
import com.smartbear.postman.script.ScriptContext;
import com.smartbear.ready.core.exception.ReadyApiException;
import net.sf.json.JSON;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.apache.commons.io.FileUtils;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.LinkedList;

public class PostmanImporter {
    public static final String NAME = "name";
    public static final String DESCRIPTION = "description";
    public static final String REQUESTS = "requests";
    public static final String URL = "url";
    public static final String METHOD = "method";
    public static final String RAW_MODE_DATA = "rawModeData";
    public static final String PRE_REQUEST_SCRIPT = "preRequestScript";
    public static final String TESTS = "tests";
    public static final String HEADERS = "headers";

    public static final String SOAP_SUFFIX = "?WSDL";

    private final TestCreator testCreator;

    public PostmanImporter(TestCreator testCreator) {
        this.testCreator = testCreator;
    }

    public WsdlProject importPostmanCollection(String filePath) {
        WsdlProject project = new WsdlProjectPro();
        File jsonFile = new File(filePath);
        String postmanJson = null;
        try {
            postmanJson = FileUtils.readFileToString(jsonFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (JsonUtil.seemsToBeJson(postmanJson)) {
            try {
                JSON json = new JsonUtil().parseTrimmedText(postmanJson);
                if (json instanceof JSONObject) {
                    JSONObject postmanCollection = (JSONObject) json;
                    project.setName(getValue(postmanCollection, NAME));
                    project.setDescription(getValue(postmanCollection, DESCRIPTION));
                    JSONArray requests = postmanCollection.getJSONArray(REQUESTS);
                    for (Object requestObject : requests) {
                        if (requestObject instanceof JSONObject) {
                            JSONObject request = (JSONObject) requestObject;
                            String uri = getValue(request, URL);
                            String method = getValue(request, METHOD);
                            String serviceName = getValue(request, DESCRIPTION);
                            String preRequestScript = getValue(request, PRE_REQUEST_SCRIPT);
                            String tests = getValue(request, TESTS);
                            String headers = getValue(request, HEADERS);

                            if (StringUtils.hasContent(preRequestScript)) {
                                processPreRequestScript(preRequestScript, project);
                            }

                            Assertable assertable = null;
                            if (isSoapRequest(uri)) {
                                String rawModeData = getValue(request, RAW_MODE_DATA);
                                String operationName = getOperationName(rawModeData);
                                WsdlRequest wsdlRequest = addWsdlRequest(project, serviceName, method, uri,
                                        operationName, rawModeData);

                                if (StringUtils.hasContent(headers)) {
                                    addHeaders(wsdlRequest, VariableUtils.convertVariables(headers));
                                }

                                if (StringUtils.hasContent(tests)) {
                                    testCreator.createTest(wsdlRequest);
                                    assertable = getTestRequestStep(project, WsdlTestRequestStep.class);
                                }
                            } else {
                                RestRequest restRequest = addRestRequest(project, serviceName, method, uri);

                                if (StringUtils.hasContent(headers)) {
                                    addHeaders(restRequest, VariableUtils.convertVariables(headers));
                                }

                                if (StringUtils.hasContent(tests)) {
                                    testCreator.createTest(restRequest);
                                    assertable = getTestRequestStep(project, RestTestRequestStep.class);
                                }
                            }

                            if (assertable != null) {
                                addAssertions(tests, project, assertable);
                            }
//                                    GenericAddRequestToTestCaseAction.perform
                        }
                    }
                }
            } catch (JSONException e) {
            }
        } else {
        }
        return project;
    }

    private void addHeaders(AbstractHttpRequest request, String headersString) {
        String[] headers = headersString.split("\\n");
        for (String header : headers) {
            String[] headerParts = header.split(":");
            if (headerParts.length == 2) {
                StringToStringsMap headersMap = request.getRequestHeaders();
                headersMap.add(headerParts[0].trim(), headerParts[1].trim());
                request.setRequestHeaders(headersMap);
            }
        }
    }

    private String getOperationName(String xml) {
        try {
            XmlObject xmlObject = XmlUtils.createXmlObject(xml, new XmlOptions());
            String xpath = "//*:Body/*[1]";
            XmlObject[] nodes = xmlObject.selectPath(xpath);
            if (nodes.length > 0) {
                return nodes[0].getDomNode().getLocalName();
            }
        } catch (XmlException e) {
            e.printStackTrace();
        }
        return null;
    }

    void addAssertions(String tests, WsdlProject project, Assertable assertable) {
        PostmanScriptTokenizer tokenizer = new PostmanScriptTokenizer();
        PostmanScriptParser parser = new PostmanScriptParser();
        try {
            LinkedList<Token> tokens = tokenizer.tokenize(tests);

            ScriptContext context = ScriptContext.prepareTestScriptContext(project, assertable);
            parser.parse(tokens, context);
        } catch (ReadyApiException e) {
            e.printStackTrace();
        }
    }

    private void processPreRequestScript(String preRequestScript, WsdlProject project) {
        PostmanScriptTokenizer tokenizer = new PostmanScriptTokenizer();
        PostmanScriptParser parser = new PostmanScriptParser();
        try {
            LinkedList<Token> tokens = tokenizer.tokenize(preRequestScript);

            ScriptContext context = ScriptContext.preparePreRequestScriptContext(project);
            parser.parse(tokens, context);
        } catch (ReadyApiException e) {
            e.printStackTrace();
        }
    }

    private RestRequest addRestRequest(WsdlProject project, String serviceName, String method, String uri) {
        RestRequest currentRequest = null;
        RequestInfo requestInfo = new RequestInfo(uri, RestRequestInterface.HttpMethod.valueOf(method));
        PostmanRestServiceBuilder builder = new PostmanRestServiceBuilder();
        try {
            currentRequest = builder.createRestServiceFromPostman(project, requestInfo);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        return currentRequest;
    }

    private WsdlRequest addWsdlRequest(WsdlProject project, String serviceName, String method, String uri, String operationName, String requestContent) {
        WsdlRequest request = null;
        try {
            WsdlInterface[] interfaces = WsdlInterfaceFactory.importWsdl(project, uri, false);
            for (WsdlInterface wsdlInterface : interfaces) {
                WsdlOperation operation = wsdlInterface.getOperationByName(operationName);
                if (operation != null) {
                    request = operation.addNewRequest("Request 1");
                    request.setRequestContent(requestContent);
                    break;
                }
            }
        } catch (SoapUIException e) {
            e.printStackTrace();
        }
        return request;
    }

    private <T> T getTestRequestStep(WsdlProject project, Class<T> stepClass) {
        if (project.getTestSuiteCount() > 0) {
            WsdlTestSuite testSuite = project.getTestSuiteAt(project.getTestSuiteCount() - 1);
            if (testSuite != null && testSuite.getTestCaseCount() > 0) {
                WsdlTestCase testCase = testSuite.getTestCaseAt(testSuite.getTestCaseCount() - 1);
                if (testCase != null && testCase.getTestStepCount() > 0) {
                    WsdlTestStep testStep = testCase.getTestStepAt(testCase.getTestStepCount() - 1);
                    if (stepClass.isInstance(testStep)) {
                        return (T) testStep;
                    }
                }
            }
        }
        return null;
    }

    private void convertParameters(RestParamsPropertyHolder propertyHolder) {
        for (TestProperty property : propertyHolder.getPropertyList()) {
            String convertedValue = VariableUtils.convertVariables(property.getValue());
            property.setValue(convertedValue);
            if (property instanceof RestParamProperty && StringUtils.hasContent(property.getDefaultValue())) {
                convertedValue = VariableUtils.convertVariables(property.getDefaultValue());
                ((RestParamProperty) property).setDefaultValue(convertedValue);
            }
        }
    }

    private boolean isSoapRequest(String url) {
        return StringUtils.hasContent(url) && url.toUpperCase().endsWith(SOAP_SUFFIX);
    }

    private String getValue(JSONObject jsonObject, String name) {
        return getValue(jsonObject, name, "");
    }


    private String getValue(JSONObject jsonObject, String field, String defaultValue) {
        final String NULL_STRING = "null";
        Object value = jsonObject.get(field);
        if (value != null) {
            String valueString = value.toString();
            if (!valueString.equals(NULL_STRING)) {
                return valueString;
            }
        }
        return defaultValue;
    }

    private class PostmanRestServiceBuilder extends ProRestServiceBuilder {
        public RestRequest createRestServiceFromPostman(WsdlProject paramWsdlProject, RequestInfo paramRequestInfo) throws MalformedURLException {
            RestResource restResource = createResource(ModelCreationStrategy.REUSE_MODEL, paramWsdlProject, paramRequestInfo.getUri());
            copyParameters(extractParams(paramRequestInfo.getUri()), restResource.getParams());
            convertParameters(restResource.getParams());
            RestMethod restMethod = addNewMethod(ModelCreationStrategy.REUSE_MODEL, restResource, paramRequestInfo.getRequestMethod());
            RestRequest restRequest = addNewRequest(restMethod);
            return restRequest;
        }
    }
}
