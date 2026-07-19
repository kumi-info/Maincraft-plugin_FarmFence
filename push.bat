@echo off
setlocal

echo === Push to PRIVATE (origin) - all files ===
git push origin main

echo.
echo === Push to PUBLIC (public) - snapshot, no private history ===
git fetch public main
set GIT_INDEX_FILE=.git\public_index
git read-tree main
git rm -r -q --cached docs/internal 2>NUL
for /f %%t in ('git write-tree') do set TREE=%%t
set GIT_INDEX_FILE=
del .git\public_index 2>NUL

for /f %%p in ('git rev-parse FETCH_HEAD:') do set PUBTREE=%%p
if "%TREE%"=="%PUBTREE%" goto :nochange

for /f %%i in ('git commit-tree %TREE% -p FETCH_HEAD -m "sync from private"') do set NEWC=%%i
git push public %NEWC%:main
goto :branch

:nochange
echo No changes - public main is up to date.
for /f %%i in ('git rev-parse FETCH_HEAD') do set NEWC=%%i

:branch
REM 引数にブランチ名を渡すと public 側にも同じ内容でそのブランチを作る (例: push.bat v2.0.0)
if not "%~1"=="" git push public %NEWC%:refs/heads/%~1

:done
echo.
echo Done!
pause
