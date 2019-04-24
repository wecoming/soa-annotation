package com.fin;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.lang3.ArrayUtils;
import org.fest.util.Arrays;
import org.jetbrains.annotations.NotNull;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.RequestMethod;

import com.google.common.collect.Lists;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

/**
 * @author liyuqi, songbinbin
 * @date 2018/8/21.
 */
public class SoaAnnotationAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(SoaAnnotationAction.class);

    private static final String SERVICE_DOC =
            "ServiceDoc(\n appkey = \"\",\n name = \"\",\n description = \"\",\n scenarios = \"\"\n )";
    private static final String INTERFACE_DOC =
            "InterfaceDoc(\n type = \"\",\n displayName = \"...服务\",\n description = \"\",\n scenarios = \"\"\n )";

    private static final String FORMATTER_OFF = "//@formatter:off";

    private static final String FORMATTER_ON = "//@formatter:on";

    private static PsiElementFactory elementFactory;

    @Override
    public void actionPerformed(AnActionEvent e) {
        elementFactory = getPsiElementFactory(e);

        PsiElement elementAt = getPsiElement(e);
        final PsiClass psiClass = elementAt == null ? null : PsiTreeUtil.getParentOfType(elementAt, PsiClass.class);
        if (psiClass == null) {
            return;
        }
        (new WriteCommandAction.Simple(psiClass.getProject(), new PsiFile[] { psiClass.getContainingFile() }) {

            @Override
            protected void run() throws Throwable {
                SoaAnnotationAction.this.buildAnnotation(psiClass);
                SoaAnnotationAction.this.addFormatterIgnoreTokenPairAndImport(psiClass);
            }
        }).execute();
    }

    private PsiElementFactory getPsiElementFactory(AnActionEvent e) {
        return JavaPsiFacade.getInstance(e.getProject()).getElementFactory();
    }

    private void addFormatterIgnoreTokenPairAndImport(PsiClass psiClass) {
        if (psiClass == null) {
            return;
        }

        List<PsiAnnotation> annotations = Lists.newArrayList(psiClass.getAnnotations());

        for (PsiMethod method : psiClass.getMethods()) {
            annotations.addAll(Lists.newArrayList(method.getAnnotations()));
        }

        for (PsiAnnotation annotation : annotations) {
            if (Objects.equals(annotation.getNameReferenceElement().getText(), "ServiceDoc")
                    || Objects.equals(annotation.getNameReferenceElement().getText(), "InterfaceDoc")
                    || Objects.equals(annotation.getNameReferenceElement().getText(), "MethodDoc")
                    || Objects.equals(annotation.getNameReferenceElement().getText(), "TypeDoc")) {
                addFormatterOffOnPair(annotation);
                addSoaAnnotationImport(annotation);
            }
        }
    }

    private void addSoaAnnotationImport(@NotNull PsiAnnotation annotation) {
        // 关闭自动添加import
        boolean addImport = false;
        PsiElement[] fileChildren = annotation.getContainingFile().getChildren();
        PsiImportList importList = null;
        for (PsiElement element : fileChildren) {
            if (element instanceof PsiImportList) {
                importList = (PsiImportList) element;
                for (PsiImportStatement importStatement : ((PsiImportList) element).getImportStatements()) {
                    if (importStatement.getQualifiedName().endsWith(annotation.getQualifiedName())) {
                        addImport = false;
                        break;
                    }
                }
            }
            if (!addImport) {
                break;
            }
        }

        if (addImport) {
            for (PsiClass importClass : buildSoaAnnotationImport(annotation)) {
                PsiImportStatement importStatement = elementFactory.createImportStatement(importClass);

                importList.add(importStatement);

                PsiJavaCodeReferenceElement javaCodeReferenceElement =
                        elementFactory.createPackageReferenceElement("com.meituan.servicecatalog.api.annotations");

                (importList.getLastChild().findElementAt(2)).addBefore(javaCodeReferenceElement,
                        (importList.getLastChild().findElementAt(2)).getFirstChild());
            }
        }
    }

    private List<PsiClass> buildSoaAnnotationImport(PsiAnnotation annotation) {
        PsiClass typeDocClass = elementFactory.createEnum("TypeDoc");
        PsiClass fieldDocClass = elementFactory.createEnum("FieldDoc");

        switch (annotation.getQualifiedName()) {
            case "TypeDoc":
                return Lists.newArrayList(typeDocClass, fieldDocClass);
            case "ServiceDoc":
                return Lists.newArrayList();
            case "InterfaceDoc":
                return Lists.newArrayList();
            case "MethodDoc":
                return Lists.newArrayList();
        }
        return Lists.newArrayList();
    }

    private void addFormatterOffOnPair(@NotNull PsiAnnotation annotation) {
        if (annotation.getParent() == null || annotation.getParent().getParent() == null) {
            return;
        }

        // 没有fields时不生成
        List<String> names = java.util.Arrays.asList(annotation.getParameterList().getAttributes()).stream()
                .map(pair -> pair.getName()).collect(Collectors.toList());
        if (Objects.equals(annotation.getQualifiedName(), "TypeDoc") && !names.contains("fields")) {
            return;
        }

        boolean addFormatterOFF = true;
        PsiElement[] methodChildren = annotation.getParent().getParent().getChildren();
        for (PsiElement child : methodChildren) {
            if (Objects.equals(child.getText(), FORMATTER_OFF)) {
                addFormatterOFF = false;
                break;
            }
        }

        if (addFormatterOFF) {
            if (annotation.getContainingFile().getName().endsWith("Enum.java")) {
                annotation.getParent().addBefore(elementFactory.createCommentFromText(FORMATTER_OFF, null), annotation);
            } else {
                annotation.getParent().getParent().addBefore(elementFactory.createCommentFromText(FORMATTER_OFF, null),
                        annotation.getParent());
            }
        }

        boolean addFormatterOn = true;
        PsiElement[] methodModifierChildren = annotation.getParent().getChildren();
        for (PsiElement child : methodModifierChildren) {
            if (Objects.equals(child.getText(), FORMATTER_ON)) {
                addFormatterOn = false;
                break;
            }
        }
        if (addFormatterOn) {
            annotation.getParent().addAfter(elementFactory.createCommentFromText(FORMATTER_ON, null), annotation);
        }
    }

    private PsiElement getPsiElement(AnActionEvent e) {
        PsiFile psiFile = e.getData(LangDataKeys.PSI_FILE);
        Editor editor = e.getData(PlatformDataKeys.EDITOR);
        if (psiFile != null && editor != null) {
            int offset = editor.getCaretModel().getOffset();
            return psiFile.findElementAt(offset);
        } else {
            e.getPresentation().setEnabled(false);
            return null;
        }
    }

    /**
     * 根据类上不同的注解生成不同的Soa注解
     *
     * @param psiClass
     */
    private void buildAnnotation(PsiClass psiClass) {
        PsiModifierList modifierList = psiClass.getModifierList();
        if (null == modifierList) {
            return;
        }

        for (PsiAnnotation annotation : modifierList.getAnnotations()) {
            if (annotation.getNameReferenceElement() == null) {
                continue;
            }
            if (Objects.equals(annotation.getNameReferenceElement().getText(), "ThriftService")) {
                buildThriftServiceSoa(modifierList, psiClass.getMethods());
                return;
            }
        }
        if (null != psiClass.getName() && psiClass.getName().endsWith("Controller")) {
            buildInterfaceAndMethodDoc(modifierList, psiClass.getMethods());
            return;
        }
        buildTypeDocSoa(modifierList, psiClass.getFields());

    }

    /**
     * 生成@ThriftService相关的注解
     *
     * @param modifierList
     * @param methods
     */
    private void buildThriftServiceSoa(PsiModifierList modifierList, @NotNull PsiMethod[] methods) {
        buildInterfaceAndMethodDoc(modifierList, methods);
        // servicedoc关闭，手动添加
        boolean needServiceDoc = false;
        for (PsiAnnotation annotation : modifierList.getAnnotations()) {
            if (annotation.getNameReferenceElement() == null) {
                continue;
            }
            if (Objects.equals(annotation.getNameReferenceElement().getText(), "ServiceDoc")) {
                needServiceDoc = false;
            }
        }
        if (needServiceDoc) {
            modifierList.addAnnotation(SERVICE_DOC);
        }
    }

    /**
     * 生成Interface和Method注解
     *
     * @param modifierList
     * @param methods
     */
    private void buildInterfaceAndMethodDoc(PsiModifierList modifierList, @NotNull PsiMethod[] methods) {
        boolean needInterfaceDoc = true;
        for (PsiAnnotation annotation : modifierList.getAnnotations()) {
            if (annotation.getNameReferenceElement() == null) {
                continue;
            }
            if (Objects.equals(annotation.getNameReferenceElement().getText(), "InterfaceDoc")) {
                needInterfaceDoc = false;
            }
        }
        if (needInterfaceDoc) {
            modifierList.addAnnotation(INTERFACE_DOC);
        }

        buildMethodSoa(methods);
    }

    /**
     * 生成Method相关注解
     *
     * @param methods
     */
    private void buildMethodSoa(@NotNull PsiMethod[] methods) {
        for (PsiMethod method : methods) {
            PsiModifierList modifierList = method.getModifierList();

            // private方法跳过
            if (modifierList.hasModifierProperty("private")) {
                continue;
            }

            boolean needMethodDoc = true;
            boolean isDeprecated = false;
            RequestMethod requestMethod = null;
            for (PsiAnnotation annotation : modifierList.getAnnotations()) {
                if (Objects.equals(annotation.getNameReferenceElement().getText(), "MethodDoc")) {
                    needMethodDoc = false;
                } else if (Objects.equals(annotation.getNameReferenceElement().getText(), "Deprecated")) {
                    isDeprecated = true;
                } else if (Objects.equals(annotation.getNameReferenceElement().getText(), "RequestMapping")) {
                    requestMethod = getRequestMethod(annotation);
                }
            }
            if (needMethodDoc) {
                List<PsiParameter> parameterList = Lists.newArrayList(method.getParameterList().getParameters());
                PsiReferenceList throwsList = method.getThrowsList();
                modifierList
                        .addAnnotation(buildMethodDoc(method, parameterList, throwsList, isDeprecated, requestMethod));
            }
        }
    }

    /**
     * 获取注解中RequestMethod的值
     *
     * @param annotation
     * @return
     */
    private RequestMethod getRequestMethod(PsiAnnotation annotation) {
        RequestMethod requestMethod = null;
        for (PsiNameValuePair nameValuePair : annotation.getParameterList().getAttributes()) {
            if (Objects.equals(nameValuePair.getName(), "method")) {
                if (Objects.equals(nameValuePair.getValue().getLastChild().getText(), "GET")) {
                    requestMethod = RequestMethod.GET;
                } else if (Objects.equals(nameValuePair.getValue().getLastChild().getText(), "POST")) {
                    requestMethod = RequestMethod.POST;
                }
            }
        }
        return requestMethod;
    }

    /**
     * 生成TypeDoc相关注解
     *
     * @param modifierList
     * @param fields
     */
    private void buildTypeDocSoa(PsiModifierList modifierList, @NotNull PsiField[] fields) {
        boolean needTypeDoc = true;
        for (PsiAnnotation annotation : modifierList.getAnnotations()) {
            if (annotation.getNameReferenceElement() == null) {
                continue;
            }
            if (Objects.equals(annotation.getNameReferenceElement().getText(), "TypeDoc")) {
                needTypeDoc = false;
                break;
            }
        }
        if (needTypeDoc) {
            modifierList.addAnnotation(buildTypeDocAnnotation(fields));
        }
    }

    private String buildTypeDocAnnotation(PsiField[] fields) {
        StringBuilder typeDocStr = new StringBuilder("TypeDoc(\n description = \"\"");

        if (!Arrays.isNullOrEmpty(fields)) {
            typeDocStr.append(",\n fields = {");

            for (PsiField field : fields) {
                try {
                    if (field.getContainingClass().getName().endsWith("Enum")
                            && !(field.getNavigationElement() instanceof PsiEnumConstant)) {
                        continue;
                    }
                    typeDocStr.append("\n @FieldDoc(name = \"");
                    typeDocStr.append(field.getName());
                    typeDocStr.append("\",  description = \"");

                    if (field.getNavigationElement() instanceof PsiEnumConstant) {
                        String description = ((PsiExpressionList) field.getLastChild()).getExpressions()[1].getText();

                        // 去掉前后多余的双引号
                        typeDocStr.append(description.substring(1, description.length() - 1));
                    }
                } catch (Exception e) {
                    LOG.warn("生成TypeDoc，解析Description时出错", e);
                    /*
                     * Messages.showMessageDialog(field.getProject(), e.toString(), "解析Description时出错",
                     * Messages.getWarningIcon());
                     */
                }

                typeDocStr.append("\"),");
            }

            typeDocStr.deleteCharAt(typeDocStr.length() - 1);
            typeDocStr.append("\n }");
        }

        typeDocStr.append("\n )");

        return typeDocStr.toString();
    }

    /**
     * 生成Method具体的注解描述
     *
     * @param parameterList
     * @param isDeprecated
     * @return
     */
    private String buildMethodDoc(PsiMethod method, List<PsiParameter> parameterList, PsiReferenceList throwsList,
            boolean isDeprecated, RequestMethod requestMethod) {
        StringBuilder annotation = new StringBuilder(
                "MethodDoc(\n tags = {\n MethodTag.PUBLIC_ACCESS\n },\n displayName = \"...接口\",\n description = \"\"");
        annotation.append(
                ", \n extensions = { \n @ExtensionDoc( \n name = \"SECURITY_PRIVILEGE\", \n content = \"数据鉴权逻辑:UPM鉴权控制，已登陆且有数据读取或操作权限\" \n ) \n }");

        if (!CollectionUtils.isEmpty(parameterList)) {
            annotation.append(",\n parameters = {");

            for (PsiParameter parameter : parameterList) {
                annotation.append("\n @ParamDoc(name = \"");
                annotation.append(parameter.getName());

                if (method.getContainingClass().getName().endsWith("Controller")) {
                    if (requestMethod == RequestMethod.GET) {
                        annotation.append(
                                "\", paramType = ParamType.REQUEST_PARAM, requiredness = Requiredness.REQUIRED, description = \"\"),");
                    } else {
                        annotation.append(
                                "\", paramType = ParamType.REQUEST_BODY, requiredness = Requiredness.REQUIRED, description = \"\"),");
                    }
                } else {
                    annotation.append("\", requiredness = Requiredness.REQUIRED, description = \"\"),");
                }

            }
            // 删除最后一个多余的逗号
            annotation.deleteCharAt(annotation.length() - 1);

            annotation.append("\n }");
        }

        if (method.getContainingClass().getName().endsWith("Controller")) {
            annotation.append(",\n responseParams = {");
            annotation.append("\n @ParamDoc(name = \"\", type = Object.class , description = \"\")");
            annotation.append("\n }");
        } else {
            annotation.append(",\n returnValueDescription = \"\"");
        }

        if (ArrayUtils.isNotEmpty(throwsList.getReferencedTypes())) {
            annotation.append(",\n exceptions = {");

            for (PsiClassType exception : throwsList.getReferencedTypes()) {
                annotation.append("\n @ExceptionDoc(description = \"\", type = ");
                annotation.append(exception.getClassName() + ".class");
                annotation.append(" ),");
            }

            annotation.deleteCharAt(annotation.length() - 1);
            annotation.append("\n }");
        }

        if (requestMethod == RequestMethod.GET) {
            annotation.append(
                    ",\n requestMethods = {\n HttpMethod.GET\n },\n restExampleUrl = \"\",\n restExampleResponseData = \"\"");
        } else if (requestMethod == RequestMethod.POST) {
            annotation.append(
                    ",\n requestMethods = {\n HttpMethod.POST\n },\n restExampleUrl = \"\",\n  postDataDescription = \"\",\n restExamplePostData = \"\",\n restExampleResponseData = \"\"");
        }

        if (isDeprecated) {
            annotation.append(",\n deprecated = \"\"");
        }

        annotation.append("\n )");
        return annotation.toString();
    }
}