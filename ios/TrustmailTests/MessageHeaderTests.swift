import Testing
@testable import Trustmail

struct MessageHeaderTests {
    /// Shrinks once scrolled into a long email, comes back near the top, and never flickers in between.
    @Test func headerCollapsesOnScroll() {
        #expect(!HeaderCollapse.collapsed(current: false, offset: 30, scrollable: 1000), "a small scroll keeps the full header")
        #expect(HeaderCollapse.collapsed(current: false, offset: 60, scrollable: 1000))
        #expect(!HeaderCollapse.collapsed(current: false, offset: 60, scrollable: 200), "a short email keeps the full header")
        #expect(HeaderCollapse.collapsed(current: true, offset: 30, scrollable: 1000), "between the two offsets nothing changes")
        #expect(!HeaderCollapse.collapsed(current: true, offset: 5, scrollable: 1000))
        #expect(!HeaderCollapse.collapsed(current: true, offset: -20, scrollable: 1000), "pulling past the top")
        // A header made tall by its warnings needs an email that scrolls further than the header is tall.
        #expect(!HeaderCollapse.collapsed(current: false, offset: 60, scrollable: 300, headerHeight: 380))
        #expect(HeaderCollapse.collapsed(current: false, offset: 60, scrollable: 400, headerHeight: 380))
    }
}
