package local.mdnode.wecomprobe;

import java.io.File;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Host-only replay. Input XML is private test evidence, never committed as production chat data. */
public final class ReplayUiDump {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("XML file, exact group name, expected text count");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Element document = factory.newDocumentBuilder().parse(new File(args[0])).getDocumentElement();
        Element root = (Element)document.getElementsByTagName("node").item(0);
        if (!"com.tencent.wework".equals(root.getAttribute("package"))) throw new AssertionError("Not a WeCom window");
        UiNode snapshot = copy(root);
        MessageExtractor.Result result = MessageExtractor.extract(snapshot, Arrays.asList(args[1]), 84802);
        WeComLayout chat = WeComLayout.identify(snapshot);
        if (chat == null || !args[1].equals(chat.name) || chat.messages().size() != result.messages.size()) {
            throw new AssertionError("WeCom web adapter did not identify the verified group and text");
        }
        if (!"READING".equals(result.status)) throw new AssertionError(result.status);
        if (result.messages.size() != Integer.parseInt(args[2])) throw new AssertionError("Unexpected text count");
        MessageTracker.Result first = MessageTracker.reconcile(java.util.Collections.emptyList(), result.messages, true);
        MessageTracker.Result repeated = MessageTracker.reconcile(first.checkpoint, result.messages, false);
        if (!repeated.added.isEmpty()) throw new AssertionError("Repeated viewport creates new records");
        System.out.println("Real UI dump replay passed: text=" + result.messages.size() + ", skipped=" + result.skipped
                + ", repeated_additions=" + repeated.added.size());
    }
    private static UiNode copy(Element source) {
        UiNode node = new UiNode();
        node.id=source.getAttribute("resource-id"); node.type=source.getAttribute("class");
        node.text=source.getAttribute("text"); node.description=source.getAttribute("content-desc");
        node.editable="android.widget.EditText".equals(node.type);
        node.visible=!"false".equals(source.getAttribute("visible-to-user"));
        Matcher bounds=Pattern.compile("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").matcher(source.getAttribute("bounds"));
        if (bounds.matches()) {
            node.left=Integer.parseInt(bounds.group(1)); node.top=Integer.parseInt(bounds.group(2));
            node.right=Integer.parseInt(bounds.group(3)); node.bottom=Integer.parseInt(bounds.group(4));
        }
        for (Node child=source.getFirstChild(); child != null; child=child.getNextSibling()) {
            if (child instanceof Element && "node".equals(child.getNodeName())) node.children.add(copy((Element)child));
        }
        return node;
    }
}
