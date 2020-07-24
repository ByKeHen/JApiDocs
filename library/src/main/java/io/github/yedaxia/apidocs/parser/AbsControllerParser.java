package io.github.yedaxia.apidocs.parser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.javadoc.JavadocBlockTag;
import io.github.yedaxia.apidocs.DocContext;
import io.github.yedaxia.apidocs.ParseUtils;
import io.github.yedaxia.apidocs.Utils;
import io.github.yedaxia.apidocs.consts.ChangeFlag;

import java.io.File;
import java.util.List;

/**
 * parse Controller Java the common part, get all request nodes
 *
 * @author yeguozhong yedaxia.github.com
 */
public abstract class AbsControllerParser {

    private CompilationUnit compilationUnit;
    private ControllerNode controllerNode;
    private File javaFile;

    public ControllerNode parse(File javaFile) {

        this.javaFile = javaFile;
        this.compilationUnit = ParseUtils.compilationUnit(javaFile);
        this.controllerNode = new ControllerNode();

        String controllerName = Utils.getJavaFileName(javaFile);
        controllerNode.setClassName(controllerName);
        compilationUnit.getClassByName(controllerName)
                .ifPresent(c -> {
                    beforeHandleController(controllerNode, c);
                    parseClassDoc(c);
                    parseMethodDocs(c);
                    afterHandleController(controllerNode, c);
                });

        return controllerNode;
    }

    File getControllerFile() {
        return javaFile;
    }

    ControllerNode getControllerNode() {
        return controllerNode;
    }

    private void parseClassDoc(ClassOrInterfaceDeclaration c) {

        c.getParentNode().get().findFirst(PackageDeclaration.class).ifPresent(pd -> {
            controllerNode.setPackageName(pd.getNameAsString());
        });

        boolean generateDocs = c.getAnnotationByName("ApiDoc").isPresent();
        controllerNode.setGenerateDocs(generateDocs);

        c.getJavadoc().ifPresent(d -> {
            String description = d.getDescription().toText();
            controllerNode.setDescription(Utils.isNotEmpty(description) ? description : c.getNameAsString());
            List<JavadocBlockTag> blockTags = d.getBlockTags();
            if (blockTags != null) {
                for (JavadocBlockTag blockTag : blockTags) {
                    if ("author".equalsIgnoreCase(blockTag.getTagName())) {
                        controllerNode.setAuthor(blockTag.getContent().toText());
                    }
                    if ("description".equalsIgnoreCase(blockTag.getTagName())) {
                        controllerNode.setDescription(blockTag.getContent().toText());
                    }
                }
            }
        });

        if (controllerNode.getDescription() == null) {
            controllerNode.setDescription(c.getNameAsString());
        }
    }

    private void parseMethodDocs(ClassOrInterfaceDeclaration c) {
        c.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getModifiers().contains(Modifier.PUBLIC))
                .forEach(m -> {

                    boolean existsApiDoc = m.getAnnotationByName("ApiDoc").isPresent();
                    if (!existsApiDoc && !controllerNode.getGenerateDocs() && !DocContext.getDocsConfig().getAutoGenerate()) {
                        return;
                    }

                    RequestNode requestNode = new RequestNode();
                    requestNode.setControllerNode(controllerNode);
                    requestNode.setAuthor(controllerNode.getAuthor());
                    requestNode.setMethodName(m.getNameAsString());
                    requestNode.setUrl(requestNode.getMethodName());
                    requestNode.setDescription(requestNode.getMethodName());

                    m.getAnnotationByClass(Deprecated.class).ifPresent(f -> {
                        requestNode.setDeprecated(true);
                    });

                    m.getJavadoc().ifPresent(d -> {
                        String description = d.getDescription().toText();
                        requestNode.setDescription(description);

                        List<JavadocBlockTag> blockTagList = d.getBlockTags();
                        for (JavadocBlockTag blockTag : blockTagList) {
                            if (blockTag.getTagName().equals("param")) {
                                ParamNode paramNode = new ParamNode();
                                paramNode.setName(blockTag.getName().get());
                                paramNode.setDescription(blockTag.getContent().toText());
                                requestNode.addParamNode(paramNode);
                            } else if (blockTag.getTagName().equals("author")) {
                                requestNode.setAuthor(blockTag.getContent().toText());
                            }
                        }
                    });

                    m.getParameters().forEach(p -> {
                        String paraName = p.getName().asString();
                        ParamNode paramNode = requestNode.getParamNodeByName(paraName);

                        if (paramNode != null && ParseUtils.isExcludeParam(p)) {
                            requestNode.getParamNodes().remove(paramNode);
                            return;
                        }

                        if (paramNode != null) {
                            Type pType = p.getType();
                            boolean isList = false;
                            if(pType instanceof ArrayType){
                                isList = true;
                                pType = ((ArrayType) pType).getComponentType();
                            }else if(ParseUtils.isCollectionType(pType.asString())){
                                List<ClassOrInterfaceType> collectionTypes = pType.getChildNodesByType(ClassOrInterfaceType.class);
                                isList = true;
                                if(!collectionTypes.isEmpty()){
                                    pType = collectionTypes.get(0);
                                }else{
                                    paramNode.setType("Object[]");
                                }
                            }else{
                                pType = p.getType();
                            }
                            if(paramNode.getType() == null){
                                final String pUnifyType = ParseUtils.unifyType(pType.asString());
                                paramNode.setType(isList ? pUnifyType + "[]": pUnifyType);
                            }
                        }
                    });

                    com.github.javaparser.ast.type.Type resultClassType = null;
                    if (existsApiDoc) {
                        AnnotationExpr an = m.getAnnotationByName("ApiDoc").get();
                        if (an instanceof SingleMemberAnnotationExpr) {
                            resultClassType = ((ClassExpr) ((SingleMemberAnnotationExpr) an).getMemberValue()).getType();
                        } else if (an instanceof NormalAnnotationExpr) {
                            for (MemberValuePair pair : ((NormalAnnotationExpr) an).getPairs()) {
                                final String pairName = pair.getNameAsString();
                                if ("result".equals(pairName) || "value".equals(pairName)) {
                                    resultClassType = ((ClassExpr) pair.getValue()).getType();
                                } else if (pairName.equals("url")) {
                                    requestNode.setUrl(((StringLiteralExpr) pair.getValue()).getValue());
                                } else if (pairName.equals("method")) {
                                    requestNode.addMethod(((StringLiteralExpr) pair.getValue()).getValue());
                                }
                            }
                        }
                    }

                    afterHandleMethod(requestNode, m);

                    if (resultClassType == null) {
                        if (m.getType() == null) {
                            return;
                        }
                        resultClassType = m.getType();
                    }

                    ResponseNode responseNode = new ResponseNode();
                    responseNode.setRequestNode(requestNode);
                    handleResponseNode(responseNode, resultClassType.getElementType(), javaFile);
                    requestNode.setResponseNode(responseNode);
                    setRequestNodeChangeFlag(requestNode);
                    controllerNode.addRequestNode(requestNode);
                });
    }

    /**
     * called before controller node has handled
     *
     * @param clazz
     */
    protected void beforeHandleController(ControllerNode controllerNode, ClassOrInterfaceDeclaration clazz) {
    }

    /**
     * called after controller node has handled
     *
     * @param clazz
     */
    protected void afterHandleController(ControllerNode controllerNode, ClassOrInterfaceDeclaration clazz) {
    }

    /**
     * handle response object
     *
     * @param responseNode
     * @param resultType
     * @param controllerFile
     */
    protected void handleResponseNode(ResponseNode responseNode, com.github.javaparser.ast.type.Type resultType, File controllerFile){
        // 解析方法返回类的泛型信息
        ((ClassOrInterfaceType) resultType).getTypeArguments().ifPresent(typeList->typeList.forEach(argType->{
            GenericNode rootGenericNode = new GenericNode();
            rootGenericNode.setFromJavaFile(controllerFile);
            rootGenericNode.setClassType(argType);
            responseNode.addGenericNode(rootGenericNode);
        }));
        ParseUtils.parseClassNodeByType(controllerFile, responseNode, resultType);
    }

    /**
     * called after request method node has handled
     */
    protected void afterHandleMethod(RequestNode requestNode, MethodDeclaration md) {
    }


    // 设置接口的类型（新/修改/一样）
    private void setRequestNodeChangeFlag(RequestNode requestNode) {
        List<ControllerNode> lastControllerNodeList = DocContext.getLastVersionControllerNodes();
        if (lastControllerNodeList == null || lastControllerNodeList.isEmpty()) {
            return;
        }

        for (ControllerNode lastControllerNode : lastControllerNodeList) {
            for (RequestNode lastRequestNode : lastControllerNode.getRequestNodes()) {
                if (lastRequestNode.getUrl().equals(requestNode.getUrl())) {
                    requestNode.setLastRequestNode(lastRequestNode);
                    requestNode.setChangeFlag(isSameRequestNodes(requestNode, lastRequestNode) ? ChangeFlag.SAME : ChangeFlag.MODIFY);
                    return;
                }
            }
        }

        requestNode.setChangeFlag(ChangeFlag.NEW);
    }

    private boolean isSameRequestNodes(RequestNode requestNode, RequestNode lastRequestNode) {

        for (String lastMethod : lastRequestNode.getMethod()) {
            if (!requestNode.getMethod().contains(lastMethod)) {
                return false;
            }
        }

        return Utils.toJson(requestNode.getParamNodes()).equals(Utils.toJson(lastRequestNode.getParamNodes()))
                && Utils.toJson(requestNode.getHeader()).equals(Utils.toJson(lastRequestNode.getHeader()))
                && requestNode.getResponseNode().toJsonApi().equals(lastRequestNode.getResponseNode().toJsonApi());
    }
}
