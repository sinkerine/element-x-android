{
  description = "Element X Android development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    android-nixpkgs = {
      url = "github:tadfisher/android-nixpkgs/stable";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    rust-overlay = {
      url = "github:oxalica/rust-overlay";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, android-nixpkgs, rust-overlay }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [ rust-overlay.overlays.default ];
          };
          androidSdk = android-nixpkgs.sdk.${system} (sdk: with sdk; [
            build-tools-35-0-0
            build-tools-36-0-0
            build-tools-37-0-0
            cmdline-tools-latest
            platform-tools
            platforms-android-34
            platforms-android-37-0
            ndk-27-2-12479018
          ]);
          rustToolchain = pkgs.rust-bin.stable.latest.default.override {
            targets = [ "x86_64-linux-android" "aarch64-linux-android" ];
          };
        in
        {
          default = pkgs.mkShell {
            packages = [ androidSdk pkgs.jdk21 pkgs.git rustToolchain pkgs.cargo-ndk ];
            ANDROID_HOME = "${androidSdk}/share/android-sdk";
            ANDROID_SDK_ROOT = "${androidSdk}/share/android-sdk";
            ANDROID_NDK_HOME = "${androidSdk}/share/android-sdk/ndk/27.2.12479018";
            JAVA_HOME = "${pkgs.jdk21}/lib/openjdk";
            NIXPKGS_ACCEPT_ANDROID_SDK_LICENSE = "1";
          };
        });
    };
}
