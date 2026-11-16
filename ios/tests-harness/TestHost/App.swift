import SwiftUI

@main
struct TestHostApp: App {
  var body: some Scene {
    WindowGroup { ContentView() }
  }
}

struct ContentView: View {
  var body: some View { Text("QueuePlayer XCTest host") }
}
