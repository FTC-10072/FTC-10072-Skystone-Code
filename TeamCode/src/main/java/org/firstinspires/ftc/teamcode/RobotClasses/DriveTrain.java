package org.firstinspires.ftc.teamcode.RobotClasses;

import android.annotation.SuppressLint;

import com.qualcomm.hardware.bosch.BNO055IMU;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.util.ElapsedTime;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.firstinspires.ftc.robotcore.external.navigation.Position;
import org.firstinspires.ftc.robotcore.external.navigation.Velocity;

public class DriveTrain {

    private LinearOpMode currentOpMode;
    private DcMotor leftFrontMotor, leftBackMotor, rightFrontMotor, rightBackMotor;

    private BNO055IMU imu;
    private Orientation lastAngles = new Orientation();
    private double globalAngle;

    private static final double TURN_P = 0.5;
    private static final double MAX_DRIVE_SPEED = 0.9;
    private static final double GAIN = 0.1;
    private static final double DEADBAND = 0.15;

    private static final AxesOrder axes = AxesOrder.XYZ;

    private static final int COUNTS_PER_REV = 1680; // count / rev
    private static final double WHEEL_DIAMETER = 4.0; // inches
    private static final double WHEEL_CIRCUMFERENCE = WHEEL_DIAMETER * 3.141592; // distance / rev
    private static final double COUNTS_PER_INCH = COUNTS_PER_REV / WHEEL_CIRCUMFERENCE;

    public DriveTrain(){}

    public void init(HardwareRobot robot, LinearOpMode opMode){
        currentOpMode = opMode;
        leftFrontMotor = robot.leftFrontMotor;
        leftBackMotor = robot.leftBackMotor;
        rightFrontMotor = robot.rightFrontMotor;
        rightBackMotor = robot.rightBackMotor;

        opMode.telemetry.addData("Mode", "calibrating...");
        opMode.telemetry.update();
        imu = robot.imu;
        while(currentOpMode.opModeIsActive() && !imu.isGyroCalibrated()){
            currentOpMode.sleep(50);
        }
        opMode.telemetry.addData("Mode", "finished calibrating.");
        opMode.telemetry.addData("imu calib status", imu.getCalibrationStatus().toString());
        opMode.telemetry.update();

        resetAngle();
    }

    // drive to specified distance with specified precision
    @SuppressLint("Assert")
    public boolean driveToDistance(double targetDistance, double timeout){
        if(currentOpMode.opModeIsActive()) {
            assert timeout > 0;
            // set new target position
            int newLeftFrontTarget = leftFrontMotor.getCurrentPosition() + (int) (COUNTS_PER_INCH * targetDistance);
            int newLeftBackTarget = leftBackMotor.getCurrentPosition() + (int) (COUNTS_PER_INCH * targetDistance);
            int newRightFrontTarget = rightFrontMotor.getCurrentPosition() + (int) (COUNTS_PER_INCH * targetDistance);
            int newRightBackTarget = rightBackMotor.getCurrentPosition() + (int) (COUNTS_PER_INCH * targetDistance);

            leftFrontMotor.setTargetPosition(newLeftFrontTarget);
            leftBackMotor.setTargetPosition(newLeftBackTarget);
            rightFrontMotor.setTargetPosition(newRightFrontTarget);
            rightBackMotor.setTargetPosition(newRightBackTarget);

            leftFrontMotor.setMode(DcMotor.RunMode.RUN_TO_POSITION);
            leftBackMotor.setMode(DcMotor.RunMode.RUN_TO_POSITION);
            rightFrontMotor.setMode(DcMotor.RunMode.RUN_TO_POSITION);
            rightBackMotor.setMode(DcMotor.RunMode.RUN_TO_POSITION);

            // reset time
            ElapsedTime time = new ElapsedTime();
            // loop until diff is within precision or timeout
            while (currentOpMode.opModeIsActive() && time.seconds() < timeout
                    && leftFrontMotor.isBusy() && leftBackMotor.isBusy()
                    && rightFrontMotor.isBusy() && rightBackMotor.isBusy()) {
                // set power with correction
                setLeftPower(1.0, MAX_DRIVE_SPEED, checkDirection());
                setRightPower(1.0, MAX_DRIVE_SPEED);
            }
            // check if finished
            if (time.seconds() > timeout) return false;
            // stop motors
            setLeftPower(0.0, 0.0);
            setRightPower(0.0, 0.0);
            // return that it ended under timeout
            return true;
        }
        return false;
    }

    // turn to specific degree. for use in Auto
    // positive is to the left, negative is to the right
    @SuppressLint("Assert")
    public boolean turnToDegree(double targetAngle, double precision, double timeout){
        // assert values
        assert precision > 0;
        assert timeout > 0;
        setMode(DcMotor.RunMode.RUN_USING_ENCODER);
        // make sure targetAngle is bounded
        if(targetAngle > 180.0) targetAngle = 179.9;
        if(targetAngle < -180.0) targetAngle = -179.9;
        // reset angle
        resetAngle();
        // loop values
        double currentAngle = getAngle();
        double diff = currentAngle - targetAngle;
        double power;
        // reset time
        ElapsedTime time = new ElapsedTime();
        while(currentOpMode.opModeIsActive() && time.seconds() < timeout
                && -precision < diff && diff < precision){
            // set power
            power = diff * TURN_P;
            setLeftPower(power, MAX_DRIVE_SPEED);
            setRightPower(-power, MAX_DRIVE_SPEED);
            // update values
            currentAngle = getAngle();
            diff = currentAngle - targetAngle;
            // sleep
            currentOpMode.sleep(50);
        }
        // check if finished
        if(time.seconds() > timeout) return false;
        // stop motors
        setLeftPower(0.0, 0.0);
        setRightPower(0.0, 0.0);
        resetAngle();
        return true;
    }


    public void arcadeDrive(double move, double turn){
        move = boundValue(move);
        move = deadband(move, DEADBAND);
        turn = boundValue(turn);
        turn = deadband(turn, DEADBAND);

        // Combine drive and turn for blended motion.
        double left  = move + turn;
        double right = move - turn;

        // Normalize the values so neither exceed +/- 1.0
        double max = Math.max(Math.abs(left), Math.abs(right));
        if (max > 1.0)
        {
            left /= max;
            right /= max;
        }

        // Output the safe vales to the motor drives.
        setLeftPower(left, MAX_DRIVE_SPEED);
        setRightPower(right, MAX_DRIVE_SPEED);
    }

    // normalize power and set left motors to that power (with correction)
    private void setLeftPower(double power, double maxPower, double correction){
        power = boundValue(power) * maxPower;
        leftFrontMotor.setPower(power + correction);
        leftBackMotor.setPower(power + correction);
    }

    // normalize power and set left motors
    private void setLeftPower(double power, double maxPower){
        power = boundValue(power) * maxPower;
        leftFrontMotor.setPower(power);
        leftBackMotor.setPower(power);
    }

    // normalize power and set right motors to that power
    private void setRightPower(double power, double maxPower){
        power = boundValue(power) * maxPower;
        rightFrontMotor.setPower(power);
        rightBackMotor.setPower(power);
    }

    // bound value to -1.0, 1.0
    private double boundValue(double value){
        if(value > 1.0) return 1.0;
        else if(value < -1.0) return -1.0;
        else return value;
    }

    // return 0 if abs(value) is less than band
    private double deadband(double value, double band){
        if(-band < value && value < band) return 0;
        return value;
    }

    // set all motor modes
    private void setMode(DcMotor.RunMode mode){
        leftFrontMotor.setMode(mode);
        leftBackMotor.setMode(mode);
        rightFrontMotor.setMode(mode);
        rightBackMotor.setMode(mode);
    }

    // reset the gyro angle
    private void resetAngle(){
        lastAngles = imu.getAngularOrientation(AxesReference.INTRINSIC, axes, AngleUnit.DEGREES);
        globalAngle = 0;
    }

    // get the current gyro angle
    // positive is to the left, negative is to the right
    private double getAngle(){
        Orientation newAngles = imu.getAngularOrientation(AxesReference.INTRINSIC, axes, AngleUnit.DEGREES);
        double deltaAngle = newAngles.firstAngle - lastAngles.firstAngle;
        if(deltaAngle < -180){
            deltaAngle += 360;
        }
        else if(deltaAngle > 180){
            deltaAngle -= 360;
        }

        globalAngle += deltaAngle;
        lastAngles = newAngles;

        return globalAngle;
    }

    // get correction factor for driving forward
    private double checkDirection(){
        double angle = getAngle();
        return angle * GAIN;
    }
}
