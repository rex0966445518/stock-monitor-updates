#ifndef AppVersion
  #define AppVersion "0.2.0"
#endif
[Setup]
AppId={{26C26027-148C-4F4F-B192-30E6AB470B2E}
AppName=三竹盤中轉強偵測器
AppVersion={#AppVersion}
AppPublisher=雷允澤
AppPublisherURL=https://github.com/rex0966445518/stock-monitor-updates
AppSupportURL=https://github.com/rex0966445518/stock-monitor-updates/issues
AppUpdatesURL=https://github.com/rex0966445518/stock-monitor-updates/releases/tag/mitake-latest
DefaultDirName={localappdata}\Programs\MitakeDetector
DefaultGroupName=三竹盤中轉強偵測器
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir=release-output
OutputBaseFilename=MitakeDetector-Setup-v{#AppVersion}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\MitakeDetector.exe
CloseApplications=yes
RestartApplications=no
AppMutex=Local\RexMitakeDetector
SetupLogging=yes
VersionInfoVersion={#AppVersion}.0
[Languages]
Name: "chinesetraditional"; MessagesFile: "compiler:Languages\ChineseTraditional.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"
[Tasks]
Name: "desktopicon"; Description: "建立桌面捷徑"; GroupDescription: "捷徑："
[Files]
Source: "dist\MitakeDetector.exe"; DestDir: "{app}"; Flags: ignoreversion
[Icons]
Name: "{group}\三竹盤中轉強偵測器"; Filename: "{app}\MitakeDetector.exe"
Name: "{autodesktop}\三竹盤中轉強偵測器"; Filename: "{app}\MitakeDetector.exe"; Tasks: desktopicon
[Run]
Filename: "{app}\MitakeDetector.exe"; Description: "啟動三竹盤中轉強偵測器"; Flags: nowait; Check: ShouldLaunch
[Code]
function ShouldLaunch: Boolean;
begin
  Result := (not WizardSilent) or (ExpandConstant('{param:LAUNCHAPP|0}') = '1');
end;
