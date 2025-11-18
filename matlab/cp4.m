data = readtable("rpm_log.txt");
time = data.time;
rpm = data.RPM;

sys = tf(6500,[3000,1]);
[y,t] = step(sys);

figure;
plot(time,rpm);
hold on;
plot(t,y);


xlabel("time(ms)");
ylabel("RPM");