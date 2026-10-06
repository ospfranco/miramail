import Testing
@testable import Trustmail

struct SenderTests {
    @Test func namesTheSender() {
        #expect(senderName("Ann <ANN@Example.com>") == "Ann")
        #expect(senderName("\"Smith, Ann\" <ann@example.com>") == "Smith, Ann")
        #expect(senderName("<ann@example.com>") == "ann@example.com")
        #expect(senderName("Ann@Example.com") == "ann@example.com")
    }

    @Test func avatarShowsTheInitialOnAColorTiedToTheAddress() {
        #expect(SenderAvatar("Ann <ANN@Example.com>") == SenderAvatar("ann@example.com"))
        #expect(SenderAvatar("Ann <ann@example.com>").initial == "A")
        #expect(SenderAvatar("Ann <ann@example.com>").hexColor == "3949AB")
        #expect(SenderAvatar("GitHub <noreply@github.com>").hexColor == "43A047")
        #expect(SenderAvatar("émile <émile@example.fr>").initial == "É")
        #expect(SenderAvatar("émile <émile@example.fr>").hexColor == "F4511E")
        #expect(SenderAvatar("<...@...>").initial == "?")
    }
}
