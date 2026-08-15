package keystrokesmod.script;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ScriptSourceTransformer {
    private ScriptSourceTransformer() {}

    static String supportNestedTypes(String source) {
        if (source == null || source.isEmpty()) return source;
        List<Token> tokens = tokenize(source);
        Map<Integer, Integer> matchingBraces = findMatchingBraces(tokens);
        List<TypeDeclaration> declarations = findTypeDeclarations(tokens, matchingBraces);
        attachMemberParents(declarations);
        List<Integer> insertionPoints = new ArrayList<>();
        for (TypeDeclaration d : declarations) {
            if (d.eligibleMember && d.kind == TypeKind.CLASS && becomesStatic(d) && !d.declaredStatic)
                insertionPoints.add(d.keywordPosition);
        }
        if (insertionPoints.isEmpty()) return source;
        insertionPoints.sort(Collections.reverseOrder());
        StringBuilder transformed = new StringBuilder(source);
        for (int p : insertionPoints) transformed.insert(p, "static ");
        return transformed.toString();
    }

    private static boolean becomesStatic(TypeDeclaration d) {
        if (d.kind != TypeKind.CLASS) return true;
        if (d.staticState != null) return d.staticState;
        d.staticState = d.declaredStatic;
        if (!d.staticState) {
            for (TypeDeclaration child : d.memberChildren) {
                if (child.eligibleMember && becomesStatic(child)) { d.staticState = true; break; }
            }
        }
        return d.staticState;
    }

    private static void attachMemberParents(List<TypeDeclaration> declarations) {
        declarations.sort(Comparator.comparingInt(d -> d.keywordToken));
        for (TypeDeclaration d : declarations) {
            TypeDeclaration enclosing = null;
            for (TypeDeclaration c : declarations) {
                if (c.keywordToken >= d.keywordToken) break;
                if (c.bodyOpenToken < d.keywordToken && c.bodyCloseToken > d.keywordToken
                        && (enclosing == null || c.bodyOpenToken > enclosing.bodyOpenToken))
                    enclosing = c;
            }
            if (enclosing == null) { d.eligibleMember = d.depth == 0; continue; }
            boolean direct = d.depth == enclosing.depth + 1;
            d.eligibleMember = direct && enclosing.eligibleMember;
            if (direct) enclosing.memberChildren.add(d);
        }
    }

    private static List<TypeDeclaration> findTypeDeclarations(List<Token> tokens, Map<Integer, Integer> matchingBraces) {
        List<TypeDeclaration> result = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            TypeKind kind = typeKind(t.text);
            if (kind == null || isClassLiteral(tokens, i)) continue;
            int bodyOpen = findTypeBody(tokens, i);
            Integer bodyClose = matchingBraces.get(bodyOpen);
            if (bodyOpen == -1 || bodyClose == null) continue;
            TypeDeclaration d = new TypeDeclaration();
            d.kind = kind; d.keywordToken = i; d.keywordPosition = t.position;
            d.bodyOpenToken = bodyOpen; d.bodyCloseToken = bodyClose; d.depth = t.depth;
            d.declaredStatic = kind != TypeKind.CLASS || hasStaticModifier(tokens, i);
            result.add(d);
        }
        return result;
    }

    private static int findTypeBody(List<Token> tokens, int kw) {
        int depth = tokens.get(kw).depth, parens = 0, brackets = 0;
        for (int i = kw + 1; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.depth < depth) return -1;
            if (t.depth != depth) continue;
            if ("(".equals(t.text)) parens++;
            else if (")".equals(t.text) && parens > 0) parens--;
            else if ("[".equals(t.text)) brackets++;
            else if ("]".equals(t.text) && brackets > 0) brackets--;
            else if ("{".equals(t.text) && parens == 0 && brackets == 0) return i;
            else if (";".equals(t.text) && parens == 0 && brackets == 0) return -1;
        }
        return -1;
    }

    private static boolean hasStaticModifier(List<Token> tokens, int kw) {
        int depth = tokens.get(kw).depth;
        for (int i = kw - 1; i >= 0; i--) {
            Token t = tokens.get(i);
            if (t.depth < depth) break;
            if (t.depth > depth) continue;
            if (";".equals(t.text) || "{".equals(t.text) || "}".equals(t.text)) break;
            if ("static".equals(t.text)) return true;
        }
        return false;
    }

    private static boolean isClassLiteral(List<Token> tokens, int i) {
        return i > 0 && "class".equals(tokens.get(i).text) && ".".equals(tokens.get(i - 1).text);
    }

    private static TypeKind typeKind(String token) {
        if ("class".equals(token)) return TypeKind.CLASS;
        if ("enum".equals(token)) return TypeKind.ENUM;
        if ("interface".equals(token)) return TypeKind.INTERFACE;
        return null;
    }

    private static Map<Integer, Integer> findMatchingBraces(List<Token> tokens) {
        Map<Integer, Integer> m = new HashMap<>();
        Deque<Integer> stack = new ArrayDeque<>();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i).text;
            if ("{".equals(t)) stack.push(i);
            else if ("}".equals(t) && !stack.isEmpty()) m.put(stack.pop(), i);
        }
        return m;
    }

    private static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int depth = 0, index = 0;
        while (index < source.length()) {
            char c = source.charAt(index);
            if (Character.isWhitespace(c)) { index++; continue; }
            if (c == '/' && index + 1 < source.length()) {
                char n = source.charAt(index + 1);
                if (n == '/') { index = skipLineComment(source, index + 2); continue; }
                if (n == '*') { index = skipBlockComment(source, index + 2); continue; }
            }
            if (c == '"' || c == '\'') { index = skipQuotedValue(source, index + 1, c); continue; }
            if (Character.isJavaIdentifierStart(c)) {
                int start = index++;
                while (index < source.length() && Character.isJavaIdentifierPart(source.charAt(index))) index++;
                tokens.add(new Token(source.substring(start, index), start, depth));
                continue;
            }
            if (c == '}') depth = Math.max(0, depth - 1);
            tokens.add(new Token(String.valueOf(c), index, depth));
            if (c == '{') depth++;
            index++;
        }
        return tokens;
    }

    private static int skipLineComment(String s, int i) { while (i < s.length() && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++; return i; }
    private static int skipBlockComment(String s, int i) { while (i + 1 < s.length()) { if (s.charAt(i) == '*' && s.charAt(i+1) == '/') return i + 2; i++; } return s.length(); }
    private static int skipQuotedValue(String s, int i, char q) { boolean esc = false; while (i < s.length()) { char c = s.charAt(i++); if (esc) esc = false; else if (c == '\\') esc = true; else if (c == q) break; } return i; }

    private enum TypeKind { CLASS, ENUM, INTERFACE }

    private static final class Token {
        final String text; final int position; final int depth;
        Token(String text, int position, int depth) { this.text = text; this.position = position; this.depth = depth; }
    }

    private static final class TypeDeclaration {
        TypeKind kind; int keywordToken, keywordPosition, bodyOpenToken, bodyCloseToken, depth;
        boolean declaredStatic, eligibleMember; Boolean staticState;
        final List<TypeDeclaration> memberChildren = new ArrayList<>();
    }
}
