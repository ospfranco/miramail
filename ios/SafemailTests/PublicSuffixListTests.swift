import Foundation
import Testing
@testable import Safemail

struct PublicSuffixListTests {
    /// Expected values were produced by `tldts.getDomain()`, which the Expo app used.
    @Test func matchesTldtsReferenceTable() throws {
        let lines = Fixture.text("domains", ext: "jsonl").split(separator: "\n")
        #expect(lines.count > 20)
        for line in lines {
            let row = try JSONSerialization.jsonObject(with: Data(line.utf8)) as! [Any]
            let host = row[0] as! String
            let expected = row[1] as? String
            #expect(PublicSuffixList.shared.registrableDomain(of: host) == expected, "host: \(host)")
        }
    }

    @Test func parsesRuleKinds() {
        let list = PublicSuffixList(contents: "com\nco.uk\n*.ck\n!www.ck\n")
        #expect(list.registrableDomain(of: "a.b.example.co.uk") == "example.co.uk")
        #expect(list.registrableDomain(of: "co.uk") == nil)
        #expect(list.registrableDomain(of: "foo.ck") == nil)
        #expect(list.registrableDomain(of: "bar.foo.ck") == "bar.foo.ck")
        #expect(list.registrableDomain(of: "x.www.ck") == "www.ck")
        #expect(list.registrableDomain(of: "") == nil)
    }
}
